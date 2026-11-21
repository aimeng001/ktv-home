package com.homektv.musicsource;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class MusicSourceSearchService {
    private final Map<MusicProvider, MusicMetadataProvider> providers = new EnumMap<>(MusicProvider.class);
    private final MusicSourceConfigService configService;
    private final ExternalTrackStorage storage;
    private final ExternalTrackMatcher matcher;
    private final SongRepository songRepository;

    public MusicSourceSearchService(List<MusicMetadataProvider> providers, MusicSourceConfigService configService,
                                    ExternalTrackStorage storage, ExternalTrackMatcher matcher, SongRepository songRepository) {
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
        this.configService = configService;
        this.storage = storage;
        this.matcher = matcher;
        this.songRepository = songRepository;
    }

    public SearchResponse search(String keyword, Set<MusicProvider> requestedProviders, boolean refresh) {
        String query = ProviderJson.clean(keyword, 500);
        if (query == null || query.length() < 2) throw new ApiException("MUSIC_SOURCE_QUERY_INVALID", "请输入至少 2 个字符的关键词");
        MusicSourceConfig config = configService.getConfig();
        if (!config.enabled()) throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请先在系统设置中启用音乐元数据服务");
        Set<MusicProvider> selected = selectProviders(requestedProviders, config.providers());
        selected.retainAll(config.providers());
        if (selected.isEmpty()) throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请至少启用一个音乐平台");

        List<ProviderResult> providerResults = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProviderResult>> futures = selected.stream()
                    .map(provider -> CompletableFuture.supplyAsync(() -> searchProvider(provider, query, config, refresh), executor)).toList();
            for (CompletableFuture<ProviderResult> future : futures) providerResults.add(future.join());
        }
        return new SearchResponse(query, group(providerResults), providerResults);
    }

    public ExternalTrack detail(MusicProvider provider, String externalId, boolean refresh) {
        String safeId = ProviderJson.clean(externalId, 160);
        if (safeId == null) throw new ApiException("EXTERNAL_TRACK_ID_INVALID", "外部歌曲 ID 无效");
        if (!refresh) {
            var cached = storage.track(provider, safeId, true);
            if (cached.isPresent()) return cached.get();
        }
        MusicSourceConfig config = configService.getConfig();
        requireConfigured(provider, config);
        ExternalTrack track = providers.get(provider).detail(safeId, Duration.ofSeconds(config.timeoutSeconds()));
        storage.upsertTrack(track, java.time.OffsetDateTime.now().plusDays(7));
        configService.recordSuccess(provider);
        return track;
    }

    public List<SongMatch> matches(long songId, boolean refresh) {
        return matches(songId, "", Set.of(), refresh);
    }

    public List<SongMatch> matches(long songId, String keyword, Set<MusicProvider> requestedProviders, boolean refresh) {
        Song song = songRepository.findById(songId).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        String query = keyword == null || keyword.isBlank() ? song.getTitle() + " " + song.getArtist() : keyword;
        SearchResponse result = search(query, requestedProviders, refresh);
        List<SongMatch> matches = result.groups().stream().flatMap(group -> group.sources().stream())
                .map(track -> new SongMatch(track, matcher.score(song, track)))
                .sorted(java.util.Comparator.comparingDouble(SongMatch::score).reversed()).limit(30).toList();
        matches.forEach(match -> storage.saveMatch(songId, match.track(), match.score()));
        return matches;
    }

    public BatchMatchResponse batchMatches(List<Long> songIds) {
        List<Long> ids = songIds == null ? List.of() : songIds.stream()
                .filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().toList();
        if (ids.isEmpty() || ids.size() > 50)
            throw new ApiException("MUSIC_SOURCE_BATCH_INVALID", "请选择 1 到 50 首歌曲进行元数据刮削");
        MusicSourceConfig config = configService.getConfig();
        if (!config.enabled() || config.providers().isEmpty())
            throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请先在系统设置中启用音乐元数据服务");
        List<BatchSongMatch> results = new ArrayList<>();
        int workers = Math.min(config.concurrencyLimit(), ids.size());
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<CompletableFuture<BatchSongMatch>> futures = ids.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> batchMatch(id), executor)).toList();
            futures.forEach(future -> results.add(future.join()));
        }
        long matched = results.stream().filter(result -> !result.matches().isEmpty()).count();
        return new BatchMatchResponse(results.size(), matched, results);
    }

    private BatchSongMatch batchMatch(long songId) {
        Song song = songRepository.findById(songId).orElse(null);
        if (song == null) return new BatchSongMatch(songId, "歌曲 #" + songId, "", List.of(), "歌曲不存在");
        try {
            List<SongMatch> candidates = matches(songId, false).stream().limit(5).toList();
            return new BatchSongMatch(songId, song.getTitle(), song.getArtist(), candidates, null);
        } catch (RuntimeException ex) {
            return new BatchSongMatch(songId, song.getTitle(), song.getArtist(), List.of(), publicError(ex));
        }
    }

    public List<TestResult> test(Collection<MusicProvider> requested) {
        MusicSourceConfig config = configService.getConfig();
        Set<MusicProvider> selected = requested == null || requested.isEmpty() ? config.providers() : new LinkedHashSet<>(requested);
        List<TestResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<TestResult>> futures = selected.stream().map(provider -> CompletableFuture.supplyAsync(() -> {
                try {
                    MusicMetadataProvider.ProviderHealth health = providers.get(provider).health(Duration.ofSeconds(config.timeoutSeconds()));
                    if (health.healthy()) {
                        configService.recordSuccess(provider);
                    } else {
                        configService.recordError(provider, health.error());
                    }
                    return new TestResult(provider, provider.displayName(), health.healthy(), health.latencyMs(), health.error());
                } catch (RuntimeException ex) {
                    String error = publicError(ex); configService.recordError(provider, error);
                    return new TestResult(provider, provider.displayName(), false, 0, error);
                }
            }, executor)).toList();
            futures.forEach(future -> results.add(future.join()));
        }
        return results;
    }

    private ProviderResult searchProvider(MusicProvider provider, String query, MusicSourceConfig config, boolean refresh) {
        long started = System.nanoTime();
        try {
            if (!refresh) {
                var cached = storage.cachedSearch(provider, query, config.resultLimit());
                if (cached.isPresent()) return new ProviderResult(provider, provider.displayName(), true, true, cached.get(), null, 0);
            }
            List<ExternalTrack> tracks = providers.get(provider).search(query, config.resultLimit(), Duration.ofSeconds(config.timeoutSeconds()));
            storage.saveSearch(provider, query, config.resultLimit(), tracks, config.searchCacheHours());
            configService.recordSuccess(provider);
            return new ProviderResult(provider, provider.displayName(), true, false, tracks, null, elapsed(started));
        } catch (RuntimeException ex) {
            String error = publicError(ex); configService.recordError(provider, error);
            return new ProviderResult(provider, provider.displayName(), false, false, List.of(), error, elapsed(started));
        }
    }

    private List<TrackGroup> group(List<ProviderResult> results) {
        Map<String, List<ExternalTrack>> grouped = new LinkedHashMap<>();
        results.stream().flatMap(result -> result.tracks().stream())
                .forEach(track -> grouped.computeIfAbsent(matcher.groupKey(track), ignored -> new ArrayList<>()).add(track));
        return grouped.values().stream().map(tracks -> {
            ExternalTrack representative = tracks.getFirst();
            List<LocalMatch> local = songRepository.findTop10ByTitleIgnoreCase(representative.title()).stream()
                    .map(song -> new LocalMatch(song.getId(), song.getTitle(), song.getArtist(), matcher.score(song, representative)))
                    .filter(match -> match.score() >= 0.72).toList();
            String state = local.stream().anyMatch(match -> match.score() >= 0.9) ? "LOCAL_EXISTS"
                    : local.isEmpty() ? "LOCAL_MISSING" : "POSSIBLE_MATCH";
            return new TrackGroup(representative, List.copyOf(tracks), state, local);
        }).toList();
    }

    private void requireConfigured(MusicProvider provider, MusicSourceConfig config) {
        if (!config.enabled() || !config.providers().contains(provider))
            throw new ApiException("MUSIC_PROVIDER_DISABLED", provider.displayName() + "未启用");
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    static Set<MusicProvider> selectProviders(Set<MusicProvider> requested, Set<MusicProvider> configured) {
        return new LinkedHashSet<>(requested == null || requested.isEmpty() ? configured : requested);
    }

    private static String publicError(Throwable ex) {
        String value = ex.getMessage();
        return ProviderJson.clean(value == null ? "平台暂时不可用" : value, 300);
    }

    public record SearchResponse(String query, List<TrackGroup> groups, List<ProviderResult> providers) {}
    public record ProviderResult(MusicProvider provider, String displayName, boolean success, boolean cached,
                                 List<ExternalTrack> tracks, String error, long latencyMs) {}
    public record TrackGroup(ExternalTrack representative, List<ExternalTrack> sources, String localState,
                             List<LocalMatch> localMatches) {}
    public record LocalMatch(long songId, String title, String artist, double score) {}
    public record SongMatch(ExternalTrack track, double score) {}
    public record BatchMatchResponse(int total, long matched, List<BatchSongMatch> songs) {}
    public record BatchSongMatch(long songId, String title, String artist, List<SongMatch> matches, String error) {}
    public record TestResult(MusicProvider provider, String displayName, boolean healthy, long latencyMs, String error) {}
}
