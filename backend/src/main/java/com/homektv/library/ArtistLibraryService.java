package com.homektv.library;
import com.homektv.common.ArtistCreditParser;
import com.homektv.common.ArtistKindClassifier;
import com.homektv.common.ArtistKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.common.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 歌手库服务：按歌手名称聚合歌曲，提供性别 AI 建议和人工复核应用。
 * 同名歌手不会自动合并，复核时展示代表歌曲帮助管理员判断。
 */
@Service
public class ArtistLibraryService {
    private static final int ARTIST_PAGE_SIZE = 50;
    private static final int MAX_ARTIST_PAGE_SIZE = 100;
    private static final Set<String> GENDERS = Set.of("男歌手", "女歌手", "组合", "未知");

    private final SongRepository songs;
    private final AiConfigService aiConfig;
    private final OpenAiCompatibleClient aiClient;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ArtistProfileService artistProfiles;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ArtistDirectoryProjectionService directoryProjection;
    private static final Comparator<Song> REPRESENTATIVE_ORDER = Comparator
            .comparingInt(Song::getPlayCount).reversed()
            .thenComparing(Song::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    public ArtistLibraryService(SongRepository songs, AiConfigService aiConfig,
                                OpenAiCompatibleClient aiClient, ObjectMapper mapper) {
        this.songs = songs;
        this.aiConfig = aiConfig;
        this.aiClient = aiClient;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(String keyword, String gender, Boolean reviewed, int limit) {
        return listForCompatibility(keyword, gender, reviewed, limit);
    }

    /**
     * Keeps the original unpaged endpoint bounded for older clients. New UI
     * code should use {@link #page(String, String, Boolean, int, int)} directly.
     */
    public List<Map<String, Object>> listForCompatibility(String keyword, String gender,
                                                           Boolean reviewed, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 5000));
        List<Map<String, Object>> result = new ArrayList<>(Math.min(safeLimit, MAX_ARTIST_PAGE_SIZE));
        for (int pageNumber = 0; result.size() < safeLimit; pageNumber++) {
            ArtistPage current = page(keyword, gender, reviewed, pageNumber, MAX_ARTIST_PAGE_SIZE);
            if (current.items() == null || current.items().isEmpty()) break;
            result.addAll(current.items());
            if (result.size() >= current.total()) break;
        }
        return result.stream().limit(safeLimit).toList();
    }

    /**
     * Database-backed artist directory. Prefers the materialized {@code artist_directory_stats}
     * projection so opening the admin artist library on 200k+ song catalogs never triggers a
     * full-table credit aggregation scan, falling back to live aggregation before initialization.
     */
    public ArtistPage page(String keyword, String gender, Boolean reviewed, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? ARTIST_PAGE_SIZE : size, MAX_ARTIST_PAGE_SIZE));
        String safeKeyword = keyword == null ? "" : keyword.trim();
        String safeGender = gender == null ? "" : gender.trim();

        if (directoryProjection != null) {
            Optional<ArtistDirectoryProjectionService.AdminProjectionPage> projected =
                    directoryProjection.adminPage(safeKeyword, safeGender, reviewed, safePage, safeSize);
            if (projected.isPresent()) {
                ArtistDirectoryProjectionService.AdminProjectionPage pageData = projected.get();
                List<ArtistDirectoryProjectionService.AdminArtistRow> rows =
                        pageData.rows() == null ? List.of() : pageData.rows();
                List<String> keys = rows.stream()
                        .map(ArtistDirectoryProjectionService.AdminArtistRow::artistKey)
                        .filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
                Map<String, List<Map<String, Object>>> samples = loadArtistSamples(keys);
                Map<String, ArtistProfileService.AvatarDiagnostic> diagnostics = loadAvatarDiagnostics(keys);
                List<Map<String, Object>> items = rows.stream()
                        .map(row -> buildArtistValue(
                                row.artistKey(), row.name(), row.gender(), row.reviewed(),
                                row.songCount(), row.artistKind(), row.avatarPath(),
                                samples.getOrDefault(row.artistKey(), List.of()),
                                diagnostics.get(row.artistKey())))
                        .toList();
                return new ArtistPage(items, pageData.total(), safePage, safeSize);
            }
        }

        Page<SongRepository.ArtistDirectoryProjection> rows = songs.pageArtistDirectory(
                "ok", safeKeyword, safeGender, reviewed, PageRequest.of(safePage, safeSize));
        List<SongRepository.ArtistDirectoryProjection> content = rows == null || rows.getContent() == null
                ? List.of() : rows.getContent();
        List<String> keys = content.stream().map(SongRepository.ArtistDirectoryProjection::getArtistKey)
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
        Map<String, List<Map<String, Object>>> samples = loadArtistSamples(keys);
        Map<String, ArtistProfileService.AvatarDiagnostic> diagnostics = loadAvatarDiagnostics(keys);
        List<Map<String, Object>> items = content.stream()
                .map(row -> artistValue(row,
                        samples.getOrDefault(row.getArtistKey(), List.of()),
                        diagnostics.get(row.getArtistKey())))
                .toList();
        long total = rows == null ? 0 : rows.getTotalElements();
        return new ArtistPage(items, total, safePage, safeSize);
    }

    private Map<String, List<Map<String, Object>>> loadArtistSamples(List<String> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();
        Map<String, List<Map<String, Object>>> samples = new HashMap<>();
        List<SongRepository.ArtistSongProjection> sampleRows = songs.findArtistSamples("ok", keys);
        if (sampleRows != null) {
            for (SongRepository.ArtistSongProjection sample : sampleRows) {
                if (sample == null || sample.getArtistKey() == null) continue;
                samples.computeIfAbsent(sample.getArtistKey(), ignored -> new ArrayList<>()).add(songValue(sample));
            }
        }
        return samples;
    }

    private Map<String, ArtistProfileService.AvatarDiagnostic> loadAvatarDiagnostics(List<String> keys) {
        if (artistProfiles == null || keys == null || keys.isEmpty()) return Map.of();
        return artistProfiles.avatarDiagnostics(keys);
    }

    /** 对一个歌手的代表歌曲进行 AI 分析；没有 AI 时返回可人工填写的未知建议。 */
    public Map<String, Object> analyze(String artist) {
        rejectPlaceholder(artist);
        List<Song> samples = representativeSongsFor(artist);
        if (samples.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        return analyze(artist, samples);
    }

    private Map<String, Object> analyze(String artist, List<Song> matches) {
        List<Song> samples = representativeSongs(matches);
        if (!aiConfig.isConfigured()) return suggestion("未知", 0, "LOCAL", "未配置 AI，无法可靠推断歌手类型，请人工复核", samples);
        try {
            String sampleJson = mapper.writeValueAsString(samples.stream().map(song -> Map.of(
                    "title", song.getTitle(), "artist", song.getArtist(), "language", song.getLanguage(),
                    "vocalForm", song.getVocalForm(), "tags", song.getTags() == null ? List.of() : Arrays.asList(song.getTags()))).toList());
            JsonNode result = aiClient.completeJsonPromptOnly("BULK",
                    "你是 KTV 歌手资料审核助手。根据同名歌手的代表歌曲判断歌手类型。只返回 JSON：gender（男歌手、女歌手、组合、未知之一）、confidence（0到1）、reason。证据不足必须返回未知，不要猜测。",
                    "歌手名称：" + artist + "\n代表歌曲：" + sampleJson, 700);
            String value = result.path("gender").asText(result.path("artistGender").asText("未知"));
            if (!GENDERS.contains(value)) value = "未知";
            double confidence = Math.max(0, Math.min(1, result.path("confidence").asDouble(0)));
            return suggestion(value, confidence, "AI", result.path("reason").asText("请人工确认"), samples);
        } catch (RuntimeException | java.io.IOException failure) {
            return suggestion("未知", 0, "LOCAL", "AI 调用失败，请人工复核：" + safeMessage(failure), samples);
        }
    }

    /** 批量分析歌手，只返回建议，不自动写回歌曲。 */
    public List<Map<String, Object>> analyzeBatch(Collection<String> artists) {
        if (artists == null) return List.of();
        Map<String, String> distinctArtists = new LinkedHashMap<>();
        artists.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> distinctArtists.putIfAbsent(ArtistCreditParser.key(value), value));
        List<String> names = distinctArtists.values().stream().limit(500).toList();
        if (names.isEmpty()) return List.of();
        Map<String, List<Song>> grouped = new LinkedHashMap<>();
        for (String artist : names) {
            if (ArtistKindClassifier.isPlaceholder(artist)) continue;
            List<Song> credited = songs.findRepresentativeSongsByArtistKeyAndStatus(
                    ArtistCreditParser.key(artist), "ok", PageRequest.of(0, 5));
            if (credited != null && !credited.isEmpty()) {
                grouped.put(ArtistCreditParser.key(artist), credited);
            }
        }
        AiConfigService.ResolvedConfig config = aiConfig.resolve();
        int configuredConcurrency = config == null ? 1 : config.bulkConcurrency();
        int concurrency = Math.max(1, Math.min(configuredConcurrency, names.size()));
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<CompletableFuture<Map<String, Object>>> futures = names.stream()
                    .map(artist -> CompletableFuture.supplyAsync(
                            () -> analyzeBatchItem(artist, grouped.get(ArtistCreditParser.key(artist))), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private Map<String, Object> analyzeBatchItem(String artist, List<Song> matches) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artist", artist);
        try {
            if (ArtistKindClassifier.isPlaceholder(artist)) {
                result.putAll(suggestion("未知", 0, "LOCAL", "占位歌手不参与 AI 分析，请先修正歌曲歌手信息", List.of()));
                return result;
            }
            if (matches == null || matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
            result.putAll(analyze(artist, matches));
        } catch (RuntimeException failure) {
            result.putAll(suggestion("未知", 0, "LOCAL", "分析失败，请人工复核：" + safeMessage(failure), List.of()));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> apply(String artist, String gender) {
        if (!GENDERS.contains(gender)) throw new ApiException("INVALID_ARTIST_GENDER", "歌手类型无效");
        rejectPlaceholder(artist);
        long matches = songs.countByArtistKeyAndStatus(ArtistCreditParser.key(artist), "ok");
        if (matches <= 0L) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        if (artistProfiles != null) artistProfiles.setGender(artist, gender);
        return Map.of("artist", artist, "gender", gender,
                "updated", Math.toIntExact(Math.min(matches, Integer.MAX_VALUE)));
    }

    private static void rejectPlaceholder(String artist) {
        if (ArtistKindClassifier.isPlaceholder(artist)) {
            throw new ApiException("ARTIST_PLACEHOLDER", "占位歌手不能进行类型分析，请先修正歌手信息");
        }
    }

    private List<Song> representativeSongsFor(String artist) {
        if (artist == null || artist.isBlank()) return List.of();
        String artistKey = ArtistCreditParser.key(artist);
        List<Song> result = songs.findRepresentativeSongsByArtistKeyAndStatus(
                artistKey, "ok", PageRequest.of(0, 5));
        return result == null ? List.of() : result;
    }

    private List<Song> representativeSongs(List<Song> values) {
        return values.stream().sorted(REPRESENTATIVE_ORDER).limit(5).toList();
    }

    private Map<String, Object> songValue(Song song) {
        return Map.of("id", song.getId(), "title", song.getTitle(), "artist", song.getArtist(),
                "language", song.getLanguage(), "mediaType", song.getMediaType(), "coverUrl",
                song.getCoverPath() == null ? "" : "/api/cover/" + song.getId());
    }

    private Map<String, Object> songValue(SongRepository.ArtistSongProjection song) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", song.getSongId());
        value.put("title", song.getTitle());
        value.put("artist", song.getArtist());
        value.put("language", song.getLanguage());
        value.put("mediaType", song.getMediaType());
        value.put("coverUrl", song.getCoverPath() == null ? "" : "/api/cover/" + song.getSongId());
        return value;
    }

    private Map<String, Object> artistValue(SongRepository.ArtistDirectoryProjection row,
                                            List<Map<String, Object>> samples,
                                            ArtistProfileService.AvatarDiagnostic diagnostic) {
        return buildArtistValue(
                row.getArtistKey(), row.getName(), row.getGender(), row.getReviewed(),
                row.getSongCount(), row.getArtistKind(), row.getAvatarPath(),
                samples, diagnostic);
    }

    private Map<String, Object> buildArtistValue(String artistKey,
                                                 String rawName,
                                                 String rawGender,
                                                 Boolean reviewed,
                                                 Long songCount,
                                                 String storedKind,
                                                 String avatarPath,
                                                 List<Map<String, Object>> samples,
                                                 ArtistProfileService.AvatarDiagnostic diagnostic) {
        Map<String, Object> value = new LinkedHashMap<>();
        String name = rawName == null || rawName.isBlank() ? "未知歌手" : rawName.trim();
        String kind = effectiveArtistKind(name, storedKind);
        value.put("artistKey", artistKey);
        value.put("name", name);
        value.put("gender", rawGender == null || rawGender.isBlank() ? "未知" : rawGender);
        value.put("reviewed", Boolean.TRUE.equals(reviewed));
        value.put("songCount", songCount == null ? 0L : songCount);
        value.put("artistKind", kind);
        boolean hasAvatar = !ArtistKindClassifier.isPlaceholder(name)
                && avatarPath != null && !avatarPath.isBlank();
        value.put("avatarUrl", hasAvatar
                ? "/api/artists/avatar?key=" + URLEncoder.encode(artistKey, StandardCharsets.UTF_8) : null);
        value.put("avatarStatus", hasAvatar ? "READY"
                : (diagnostic != null && diagnostic.avatarStatus() != null ? diagnostic.avatarStatus() : "PENDING"));
        value.put("avatarProvider", diagnostic != null ? diagnostic.avatarProvider() : null);
        value.put("avatarError", hasAvatar ? null : (diagnostic != null ? diagnostic.avatarError() : null));
        value.put("songs", samples);
        return value;
    }

    private static String effectiveArtistKind(String name, String storedKind) {
        ArtistKind classified = ArtistKindClassifier.classify(name);
        if (classified != ArtistKind.PERSON) return classified.name();
        return storedKind == null || storedKind.isBlank() ? classified.name() : storedKind;
    }

    private Map<String, Object> suggestion(String gender, double confidence, String source, String reason, List<Song> samples) {
        return Map.of("gender", gender, "confidence", confidence, "source", source, "reason", reason,
                "songs", samples.stream().map(this::songValue).toList());
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }

    public record ArtistPage(List<Map<String, Object>> items, long total, int page, int size) {}
}
