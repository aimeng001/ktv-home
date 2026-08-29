package com.homektv.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 歌手库服务：按歌手名称聚合歌曲，提供性别 AI 建议和人工复核应用。
 * 同名歌手不会自动合并，复核时展示代表歌曲帮助管理员判断。
 */
@Service
public class ArtistLibraryService {
    private static final int SONG_PAGE_SIZE = 500;
    private static final Set<String> GENDERS = Set.of("男歌手", "女歌手", "组合", "未知");

    private final SongRepository songs;
    private final AiConfigService aiConfig;
    private final OpenAiCompatibleClient aiClient;
    private final ObjectMapper mapper;
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
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return artistSummaries().values().stream()
                .filter(entry -> query.isBlank() || entry.name().toLowerCase(Locale.ROOT).contains(query))
                .map(ArtistSummary::toValue)
                .filter(value -> gender == null || gender.isBlank() || gender.equals(value.get("gender")))
                .filter(value -> reviewed == null || reviewed == ((Boolean) value.get("reviewed")))
                .sorted(Comparator.comparingInt((Map<String, Object> value) -> (Integer) value.get("songCount"))
                        .reversed().thenComparing(value -> (String) value.get("name")))
                .limit(Math.max(1, Math.min(limit, 5000)))
                .toList();
    }

    /** 对一个歌手的代表歌曲进行 AI 分析；没有 AI 时返回可人工填写的未知建议。 */
    public Map<String, Object> analyze(String artist) {
        List<Song> matches = songsFor(artist);
        if (matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        return analyze(artist, matches);
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
        List<String> names = artists.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(500)
                .toList();
        if (names.isEmpty()) return List.of();
        Map<String, List<Song>> grouped = new LinkedHashMap<>();
        forEachValidSong(song -> addRepresentative(grouped, normalizeArtist(song.getArtist()), song));
        AiConfigService.ResolvedConfig config = aiConfig.resolve();
        int configuredConcurrency = config == null ? 1 : config.bulkConcurrency();
        int concurrency = Math.max(1, Math.min(configuredConcurrency, names.size()));
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<CompletableFuture<Map<String, Object>>> futures = names.stream()
                    .map(artist -> CompletableFuture.supplyAsync(
                            () -> analyzeBatchItem(artist, grouped.get(normalizeArtist(artist))), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private Map<String, Object> analyzeBatchItem(String artist, List<Song> matches) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artist", artist);
        try {
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
        List<Song> matches = songsFor(artist);
        if (matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        matches.forEach(song -> {
            song.setArtistGender(gender);
            song.lockMetadata("artistGender");
        });
        songs.saveAll(matches);
        return Map.of("artist", artist, "gender", gender, "updated", matches.size());
    }

    private Map<String, ArtistSummary> artistSummaries() {
        Map<String, ArtistSummary> result = new LinkedHashMap<>();
        forEachValidSong(song -> {
            String name = song.getArtist() == null ? "未知歌手" : song.getArtist().trim();
            result.computeIfAbsent(name, ArtistSummary::new).add(song);
        });
        return result;
    }

    private void forEachValidSong(Consumer<Song> consumer) {
        for (int pageNumber = 0; ; pageNumber++) {
            Page<Song> page = songs.findByStatus("ok",
                    PageRequest.of(pageNumber, SONG_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            if (page == null || page.isEmpty()) return;
            page.getContent().stream().filter(Objects::nonNull).forEach(consumer);
            if (!page.hasNext()) return;
        }
    }

    private List<Song> songsFor(String artist) {
        if (artist == null || artist.isBlank()) return List.of();
        List<Song> direct = songs.findByArtistIgnoreCaseAndStatus(artist.trim(), "ok");
        if (direct != null && !direct.isEmpty()) return direct;
        String normalized = normalizeArtist(artist);
        List<Song> result = new ArrayList<>();
        forEachValidSong(song -> {
            if (normalized.equals(normalizeArtist(song.getArtist()))) result.add(song);
        });
        return result;
    }

    private static String normalizeArtist(String artist) {
        return artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT);
    }

    private List<Song> representativeSongs(List<Song> values) {
        return values.stream().sorted(REPRESENTATIVE_ORDER).limit(5).toList();
    }

    private void addRepresentative(Map<String, List<Song>> grouped, String key, Song song) {
        List<Song> values = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
        values.add(song);
        values.sort(REPRESENTATIVE_ORDER);
        if (values.size() > 5) values.remove(values.size() - 1);
    }

    private Map<String, Object> songValue(Song song) {
        return Map.of("id", song.getId(), "title", song.getTitle(), "artist", song.getArtist(),
                "language", song.getLanguage(), "mediaType", song.getMediaType(), "coverUrl",
                song.getCoverPath() == null ? "" : "/api/cover/" + song.getId());
    }

    private Map<String, Object> suggestion(String gender, double confidence, String source, String reason, List<Song> samples) {
        return Map.of("gender", gender, "confidence", confidence, "source", source, "reason", reason,
                "songs", samples.stream().map(this::songValue).toList());
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }

    private final class ArtistSummary {
        private final String name;
        private int songCount;
        private final Map<String, Integer> genderCounts = new HashMap<>();
        private final Map<String, Boolean> genderLocks = new HashMap<>();
        private final List<Song> representatives = new ArrayList<>(5);

        private ArtistSummary(String name) { this.name = name; }

        private String name() { return name; }

        private void add(Song song) {
            songCount++;
            String value = song.getArtistGender();
            if (GENDERS.contains(value) && !"未知".equals(value)) {
                genderCounts.merge(value, 1, Integer::sum);
                genderLocks.merge(value, song.isMetadataLocked("artistGender"), Boolean::logicalAnd);
            }
            representatives.add(song);
            representatives.sort(REPRESENTATIVE_ORDER);
            if (representatives.size() > 5) representatives.remove(representatives.size() - 1);
        }

        private String gender() {
            return genderCounts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("未知");
        }

        private boolean reviewed() {
            String value = gender();
            return !"未知".equals(value)
                    && genderCounts.getOrDefault(value, 0) == songCount
                    && Boolean.TRUE.equals(genderLocks.get(value));
        }

        private Map<String, Object> toValue() {
            return Map.of("name", name, "gender", gender(), "reviewed", reviewed(),
                    "songCount", songCount,
                    "songs", representatives.stream().map(ArtistLibraryService.this::songValue).toList());
        }
    }
}
