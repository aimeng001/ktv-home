package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.musicsource.CoverImageNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local artist avatar matching engine (P0 Local-First).
 * Automatically indexes local directories in dataPath, avatarLibraryPath,
 * and sourceLibraryPath, and connects them directly to artist_profiles.
 */
@Service
public class LocalAvatarResolver {
    private static final Logger log = LoggerFactory.getLogger(LocalAvatarResolver.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "artist-covers", "covers", "lyrics", "standby", "secrets", "postgres", ".git", "cache");
    static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 与统一图片输入上限一致
    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\(（【](.*?)[\\]\\)）】]");

    private final AppProperties props;
    private final AssetWriter assetWriter;
    private final ArtistProfileService profileService;
    private CoverImageNormalizer coverImageNormalizer;
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    public LocalAvatarResolver(AppProperties props, AssetWriter assetWriter, ArtistProfileService profileService) {
        this.props = props;
        this.assetWriter = assetWriter;
        this.profileService = profileService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setCoverImageNormalizer(CoverImageNormalizer coverImageNormalizer) {
        this.coverImageNormalizer = coverImageNormalizer;
    }

    /**
     * Resolves and imports avatars from all candidate local directories into application-owned cache.
     *
     * @return count of successfully matched and ready artist avatars
     */
    public int resolveAllCandidates() {
        if (!resolving.compareAndSet(false, true)) {
            log.info("本地歌手头像匹配任务正在执行中，跳过重复触发");
            return 0;
        }
        try {
            List<Path> candidateRoots = collectCandidateDirectories();
            if (candidateRoots.isEmpty()) return 0;

            Map<String, Path> imageIndex = new HashMap<>();
            for (Path root : candidateRoots) {
                indexDirectory(root, imageIndex);
            }
            if (imageIndex.isEmpty()) return 0;

            List<ArtistProfileService.Profile> unresolved = profileService.unresolvedProfiles(50_000);
            int matched = 0;

            for (var profile : unresolved) {
                if (profile == null || profile.artistKey() == null) continue;
                if (ArtistKindClassifier.isPlaceholder(profile.displayName())) continue;

                String key = profile.artistKey();
                Path imagePath = imageIndex.get(key);
                if (imagePath == null && profile.displayName() != null) {
                    imagePath = imageIndex.get(ArtistCreditParser.key(profile.displayName()));
                }

                if (imagePath != null && Files.isRegularFile(imagePath) && Files.isReadable(imagePath)) {
                    try {
                        if (Files.size(imagePath) > MAX_FILE_SIZE) {
                            log.warn("本地头像文件过大(>10MB)，跳过自动导入: {}", imagePath);
                            continue;
                        }
                        byte[] bytes = readBounded(imagePath);
                        if (bytes == null) {
                            log.warn("本地头像文件在读取期间超过 10MB，跳过自动导入: {}", imagePath);
                            continue;
                        }
                        String ext = extractExtension(imagePath);
                        if (coverImageNormalizer != null) {
                            bytes = coverImageNormalizer.normalize(bytes);
                            ext = "jpg";
                        }
                        String relPath = assetWriter.writeArtistCover(profile.artistKey(), bytes, ext);
                        profileService.setAvatarReady(profile.artistKey(), "LOCAL_PACK", relPath);
                        matched++;
                    } catch (Exception e) {
                        log.warn("读取本地歌手头像失败：{} - {}", imagePath, e.getMessage());
                    }
                }
            }
            if (matched > 0) {
                log.info("本地头像匹配完成：共扫描 {} 个有效本地头像，成功匹配并导入 {} 位歌手", imageIndex.size(), matched);
            }
            return matched;
        } finally {
            resolving.set(false);
        }
    }

    /**
     * Reads an image with a second, streamed size boundary. The initial Files.size check is
     * only advisory because a file can be replaced or grow before it is opened.
     */
    static byte[] readBounded(Path imagePath) throws IOException {
        try (var input = Files.newInputStream(imagePath)) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_SIZE + 1);
            return bytes.length > MAX_FILE_SIZE ? null : bytes;
        }
    }

    public List<Path> collectCandidateDirectories() {
        List<Path> paths = new ArrayList<>();
        // 1. avatar-library 独立头像库目录
        if (props.getAvatarLibraryPath() != null && !props.getAvatarLibraryPath().isBlank()) {
            Path p = Path.of(props.getAvatarLibraryPath());
            if (Files.isDirectory(p)) {
                paths.add(p);
            }
        }
        // 2. data 目录（内部由 indexDirectory 自动跳过 EXCLUDED_DIRS 系统子目录）
        if (props.getDataPath() != null && !props.getDataPath().isBlank()) {
            Path dataDir = Path.of(props.getDataPath());
            if (Files.isDirectory(dataDir)) {
                paths.add(dataDir);
            }
        }
        // 3. source-music 目录下的写真/头像子目录
        if (props.getSourceLibraryPath() != null && !props.getSourceLibraryPath().isBlank()) {
            Path src = Path.of(props.getSourceLibraryPath());
            if (Files.isDirectory(src)) {
                for (String sub : List.of("avatars", "artist-covers", "artist-packs", "歌手头像", "歌手写真")) {
                    Path subDir = src.resolve(sub);
                    if (Files.isDirectory(subDir)) paths.add(subDir);
                }
            }
        }
        return paths;
    }

    public void indexDirectory(Path dir, Map<String, Path> index) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.walk(dir, 3)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                // 确保跳过被排除系统目录下的文件
                for (Path parent = file.getParent(); parent != null && !parent.equals(dir.getParent()); parent = parent.getParent()) {
                    String parentName = parent.getFileName() != null ? parent.getFileName().toString().toLowerCase(Locale.ROOT) : "";
                    if (EXCLUDED_DIRS.contains(parentName)) {
                        return;
                    }
                }
                String filename = file.getFileName().toString();
                int dot = filename.lastIndexOf('.');
                if (dot > 0) {
                    String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                    if (IMAGE_EXTENSIONS.contains(ext)) {
                        String rawName = filename.substring(0, dot).trim();
                        indexCandidateKeys(rawName, file, index);
                    }
                }
            });
        } catch (IOException e) {
            log.debug("扫描本地头像目录跳过: {} - {}", dir, e.getMessage());
        }
    }

    private void indexCandidateKeys(String rawName, Path file, Map<String, Path> index) {
        if (rawName == null || rawName.isBlank()) return;
        // 1. 原始文件名 Key（如 "王力宏 (Leehom Wang)"）
        String rawKey = ArtistCreditParser.key(rawName);
        if (!rawKey.isBlank()) index.putIfAbsent(rawKey, file);

        // 2. 去除括号的主名（如 "王力宏"）
        String cleaned = BRACKET_PATTERN.matcher(rawName).replaceAll(" ").trim();
        String cleanedKey = ArtistCreditParser.key(cleaned);
        if (!cleanedKey.isBlank()) index.putIfAbsent(cleanedKey, file);

        // 3. 括号内的别名/英文名（如 "Leehom Wang"）
        Matcher matcher = BRACKET_PATTERN.matcher(rawName);
        while (matcher.find()) {
            String bracketContent = matcher.group(1).trim();
            String bracketKey = ArtistCreditParser.key(bracketContent);
            if (!bracketKey.isBlank()) index.putIfAbsent(bracketKey, file);
        }
    }

    private String extractExtension(Path path) {
        String fn = path.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(dot + 1).toLowerCase(Locale.ROOT) : "jpg";
    }
}
