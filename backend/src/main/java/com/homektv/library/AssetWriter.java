package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 歌词/封面缓存落盘（P1.4）。写入 data 目录的 lyrics/ 与 covers/ 子目录，
 * 以歌曲指纹命名，返回相对 data 目录的路径存入 songs 表。
 */
@Component
public class AssetWriter {

    private static final Logger log = LoggerFactory.getLogger(AssetWriter.class);
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    static final int MAX_LYRIC_BYTES = 1 * 1024 * 1024;
    static final long MAX_ARTIST_COVER_CACHE_BYTES = 1L * 1024 * 1024 * 1024;
    private static final List<String> ARTIST_COVER_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    private final Path dataRoot;
    private final AppProperties props;
    private final Object artistCoverCapacityLock = new Object();
    private long cachedArtistCoverBytes = -1L;
    private FileTime cachedArtistCoverDirMtime;

    public AssetWriter(AppProperties props) {
        this.props = props;
        this.dataRoot = Path.of(props.getDataPath());
        LibraryModePolicy.requireCacheOutsideExternalSource(props, dataRoot);
    }

    /** 写歌词缓存，新文件按 2 字符前缀分桶：lyrics/{xx}/{fingerprint}.lrc */
    public String writeLyric(String fingerprint, String lyricText) {
        String rel = "lyrics/" + shardPrefix(fingerprint) + "/" + fingerprint + ".lrc";
        byte[] bytes = lyricText == null ? new byte[0] : lyricText.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LYRIC_BYTES) {
            throw new ApiException("LYRIC_TOO_LARGE", "歌词不能超过 1MB");
        }
        write(rel, bytes);
        return rel;
    }

    /** 写封面缓存，新文件按 2 字符前缀分桶：covers/{xx}/{fingerprint}.{ext} */
    public String writeCover(String fingerprint, byte[] image, String ext) {
        String rel = "covers/" + shardPrefix(fingerprint) + "/" + fingerprint + "." + (ext == null ? "jpg" : ext);
        requireImageSize(image);
        write(rel, image);
        return rel;
    }

    /** Write an artist avatar to an application-owned 2-char bucketed cache, keyed by artist identity. */
    public String writeArtistCover(String artistKey, byte[] image, String ext) {
        String hash = digest(artistKey);
        String rel = "artist-covers/" + hash.substring(0, 2) + "/" + hash + "." + (ext == null ? "jpg" : ext);
        requireImageSize(image);
        Path target = dataRoot.resolve(rel);
        long previousSize = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? safeFileSize(target) : 0L;
        long delta = Math.max(0L, image.length - previousSize);
        if (delta > 0 && !hasArtistCoverCapacity(delta)) {
            throw new ApiException("ARTIST_COVER_CACHE_FULL", "歌手头像缓存已达到容量上限");
        }
        write(rel, image);
        return rel;
    }

    private static long safeFileSize(Path target) {
        try {
            return Files.size(target);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /**
     * Checks whether one bounded network avatar can be added without exceeding
     * the application-owned artist-cover cache. This method never deletes a
     * file; the existing orphan sweeper remains the only cleanup mechanism.
     */
    public boolean hasArtistCoverCapacity(long incomingBytes) {
        if (incomingBytes < 0 || incomingBytes > MAX_ARTIST_COVER_CACHE_BYTES) return false;
        Path normalizedDataRoot = dataRoot.toAbsolutePath().normalize();
        Path root = normalizedDataRoot.resolve("artist-covers").normalize();
        if (!root.startsWith(normalizedDataRoot)) return false;
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            Path realDataRoot = normalizedDataRoot.toRealPath();
            Path realRoot = root.toRealPath();
            if (!realRoot.startsWith(realDataRoot)) return false;
            synchronized (artistCoverCapacityLock) {
                FileTime dirMtime = Files.getLastModifiedTime(realRoot, LinkOption.NOFOLLOW_LINKS);
                if (cachedArtistCoverBytes < 0 || !Objects.equals(dirMtime, cachedArtistCoverDirMtime)) {
                    long scanned = scanArtistCoverDirectoryBytes(realRoot);
                    if (scanned < 0) return false;
                    cachedArtistCoverBytes = scanned;
                    cachedArtistCoverDirMtime = dirMtime;
                }
                return cachedArtistCoverBytes <= MAX_ARTIST_COVER_CACHE_BYTES - incomingBytes;
            }
        } catch (IOException failure) {
            log.debug("歌手头像缓存容量无法确认：{}", failure.getMessage());
            return false;
        }
    }

    private long scanArtistCoverDirectoryBytes(Path realRoot) throws IOException {
        final long[] used = {0L};
        final boolean[] invalid = {false};
        Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink() || !dir.normalize().startsWith(realRoot)) {
                    invalid[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink() || !file.normalize().startsWith(realRoot)) {
                    invalid[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile()) {
                    used[0] = saturatedAdd(used[0], attrs.size());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return invalid[0] ? -1L : used[0];
    }

    private static long saturatedAdd(long current, long delta) {
        if (delta <= 0) return current;
        return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
    }

    /**
     * Checks an application-owned cache path without treating a stale database
     * path as a valid avatar. The path is relative to app.data-path and is
     * never allowed to escape that directory or the external-library boundary.
     */
    /** Returns true when the configured cache root directory itself is present and readable. */
    public boolean isCacheStorageAvailable() {
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            return Files.isDirectory(cacheRoot) && Files.isReadable(cacheRoot);
        } catch (RuntimeException failure) {
            log.debug("缓存根目录不可用：{}", failure.getMessage());
            return false;
        }
    }

    public boolean isReadableCache(String relativePath) {
        return readableCachePath(relativePath).isPresent();
    }

    /**
     * Probes whether a cached avatar file already exists under /data/artist-covers
     * for the given artist key (e.g. after DB rebuild or container upgrade).
     */
    public Optional<String> findExistingArtistCover(String artistKey) {
        if (artistKey == null || artistKey.isBlank()) return Optional.empty();
        String hash = digest(artistKey);
        String prefix = hash.substring(0, 2);
        for (String ext : ARTIST_COVER_EXTENSIONS) {
            String rel = "artist-covers/" + prefix + "/" + hash + "." + ext;
            if (isReadableCache(rel)) {
                return Optional.of(rel);
            }
        }
        return Optional.empty();
    }

    /** Returns true when dataRoot/artist-covers exists as a real non-empty directory. */
    public boolean isArtistCoverDirectoryPresentAndNonEmpty() {
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            Path coverRoot = cacheRoot.resolve("artist-covers").normalize();
            if (!coverRoot.startsWith(cacheRoot) || Files.isSymbolicLink(coverRoot)
                    || !Files.isDirectory(coverRoot, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realCacheRoot = cacheRoot.toRealPath();
            Path realCoverRoot = coverRoot.toRealPath();
            if (!realCoverRoot.startsWith(realCacheRoot)) return false;
            try (Stream<Path> stream = Files.list(realCoverRoot)) {
                return stream.findAny().isPresent();
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("歌手头像缓存目录检查失败：{}", failure.getMessage());
            return false;
        }
    }

    /** Returns a verified real cache path for serving, or empty when unsafe/missing. */
    public Optional<Path> readableCachePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            Path target = cacheRoot.resolve(relativePath).normalize();
            if (!target.startsWith(cacheRoot)) return Optional.empty();
            Path realRoot = cacheRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) return Optional.empty();
            LibraryModePolicy.requireCacheOutsideExternalSource(props, realTarget);
            return Files.isRegularFile(realTarget) && Files.isReadable(realTarget)
                    ? Optional.of(realTarget) : Optional.empty();
        } catch (RuntimeException failure) {
            log.debug("资源缓存路径不可读：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        } catch (IOException failure) {
            log.debug("资源缓存路径不可读：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        }
    }

    Path dataRootForCleanup() {
        return dataRoot.toAbsolutePath().normalize();
    }

    /** Deletes one verified application-owned cache file, if it still exists. */
    public void deleteReadableCache(String relativePath) {
        readableCachePath(relativePath).ifPresent(path -> {
            try {
                boolean isArtistCover = relativePath != null && relativePath.startsWith("artist-covers/");
                long deletedSize = isArtistCover && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        ? Files.size(path) : 0L;
                if (Files.deleteIfExists(path) && isArtistCover) {
                    adjustCachedArtistCoverBytes(-deletedSize, dataRootForCleanup().resolve("artist-covers"));
                }
            } catch (IOException failure) {
                log.debug("资源缓存清理失败：{} - {}", relativePath, failure.getMessage());
            }
        });
    }

    /**
     * Checks and returns a verified real standby logo path within dataRoot/standby/.
     * Prevents directory traversal, arbitrary data file access, and symlink escape.
     */
    public Optional<Path> readableStandbyLogo(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            Path standbyRoot = cacheRoot.resolve("standby").normalize();
            Path target = cacheRoot.resolve(relativePath).normalize();
            if (!target.startsWith(standbyRoot)) return Optional.empty();
            if (!Files.exists(standbyRoot)) return Optional.empty();

            Path realStandby = standbyRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realStandby)) return Optional.empty();

            LibraryModePolicy.requireCacheOutsideExternalSource(props, realTarget);
            return Files.isRegularFile(realTarget) && Files.isReadable(realTarget)
                    ? Optional.of(realTarget) : Optional.empty();
        } catch (Exception failure) {
            log.debug("待机 Logo 路径不合法：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        }
    }

    public String writePlaylistCover(Long playlistId, byte[] image, String ext) {
        String rel = "playlist-covers/" + playlistId + "-" + System.currentTimeMillis() + "." + (ext == null ? "jpg" : ext);
        requireImageSize(image);
        write(rel, image);
        return rel;
    }

    public String writeStandbyLogo(byte[] image, String ext) {
        String rel = "standby/logo-" + System.currentTimeMillis() + "." + (ext == null ? "png" : ext);
        requireImageSize(image);
        write(rel, image);
        return rel;
    }

    private static void requireImageSize(byte[] image) {
        if (image == null || image.length == 0) {
            throw new ApiException("INVALID_IMAGE", "图片不能为空");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new ApiException("IMAGE_TOO_LARGE", "图片不能超过 10MB");
        }
    }

    private void adjustCachedArtistCoverBytes(long delta, Path coverRoot) {
        synchronized (artistCoverCapacityLock) {
            if (cachedArtistCoverBytes < 0) return;
            cachedArtistCoverBytes = Math.max(0L, cachedArtistCoverBytes + delta);
            try {
                if (coverRoot != null && Files.isDirectory(coverRoot, LinkOption.NOFOLLOW_LINKS)) {
                    cachedArtistCoverDirMtime = Files.getLastModifiedTime(coverRoot, LinkOption.NOFOLLOW_LINKS);
                }
            } catch (IOException ignored) {
                cachedArtistCoverBytes = -1L;
                cachedArtistCoverDirMtime = null;
            }
        }
    }

    private void write(String relPath, byte[] data) {
        try {
            Path target = dataRoot.resolve(relPath);
            LibraryModePolicy.requireCacheOutsideExternalSource(props, target);
            Files.createDirectories(target.getParent());
            // Re-check the concrete target after parent directories exist. This catches
            // a symlink/junction in lyrics/, covers/, or another cache subdirectory.
            LibraryModePolicy.requireCacheOutsideExternalSource(props, target);
            boolean isArtistCover = relPath != null && relPath.startsWith("artist-covers/");
            long previousSize = isArtistCover && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(target) : 0L;
            Files.write(target, data);
            if (isArtistCover) {
                adjustCachedArtistCoverBytes(data.length - previousSize,
                        dataRoot.toAbsolutePath().normalize().resolve("artist-covers"));
            }
        } catch (IOException e) {
            log.warn("资源落盘失败：{} - {}", relPath, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private static String shardPrefix(String key) {
        if (key == null || key.isEmpty()) return "00";
        char first = 0;
        char second = 0;
        for (int i = 0, len = key.length(); i < len; i++) {
            char c = key.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) {
                if (first == 0) first = c;
                else { second = c; break; }
            } else if (c >= 'A' && c <= 'Z') {
                char lower = (char) (c + 32);
                if (first == 0) first = lower;
                else { second = lower; break; }
            }
        }
        if (first == 0) return "00";
        if (second == 0) return new String(new char[]{first, '0'});
        return new String(new char[]{first, second});
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成艺术家缓存键", failure);
        }
    }
}
