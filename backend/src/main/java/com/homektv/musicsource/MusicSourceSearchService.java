package com.homektv.musicsource;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.common.ApiException;
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
    private final Map<MusicProvider, ArtistMetadataProvider> artistProviders = new EnumMap<>(MusicProvider.class);
    private final MusicSourceConfigService configService;
    private final ExternalTrackStorage storage;
    private final ExternalTrackMatcher matcher;
    private final SongRepository songRepository;
    private ArtistAvatarFallbackChain fallbackChain;

    public MusicSourceSearchService(List<MusicMetadataProvider> providers, MusicSourceConfigService configService,
                                    ExternalTrackStorage storage, ExternalTrackMatcher matcher, SongRepository songRepository) {
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
        this.configService = configService;
        this.storage = storage;
        this.matcher = matcher;
        this.songRepository = songRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setArtistProviders(List<ArtistMetadataProvider> artistProviders) {
        if (artistProviders == null) return;
        artistProviders.forEach(provider -> this.artistProviders.put(provider.provider(), provider));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFallbackChain(ArtistAvatarFallbackChain fallbackChain) {
        this.fallbackChain = fallbackChain;
    }

    public SearchResponse search(String keyword, Set<MusicProvider> requestedProviders, boolean refresh) {
        return searchInternal(keyword, requestedProviders, refresh).response();
    }

    private SearchOutcome searchInternal(String keyword, Set<MusicProvider> requestedProviders, boolean refresh) {
        String query = ProviderJson.clean(keyword, 500);
        if (query == null || query.length() < 2) throw new ApiException("MUSIC_SOURCE_QUERY_INVALID", "请输入至少 2 个字符的关键词");
        MusicSourceConfig config = configService.getConfig();
        if (!config.enabled()) throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请先在系统设置中启用元数据服务");
        Set<MusicProvider> selected = selectProviders(requestedProviders, config.providers());
        selected.retainAll(config.providers());
        selected.removeIf(provider -> !provider.supportsSongMetadata() || !providers.containsKey(provider));
        if (selected.isEmpty()) throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请至少启用一个支持歌曲元数据的音乐平台");

        List<ProviderOutcome> outcomes = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProviderOutcome>> futures = selected.stream()
                    .map(provider -> CompletableFuture.supplyAsync(() -> searchProvider(provider, query, config, refresh), executor)).toList();
            for (CompletableFuture<ProviderOutcome> future : futures) outcomes.add(future.join());
        }
        List<ProviderResult> providerResults = outcomes.stream().map(ProviderOutcome::result).toList();
        return new SearchOutcome(new SearchResponse(query, group(providerResults), providerResults), outcomes);
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
        SearchOutcome outcome = searchInternal(query, requestedProviders, refresh);
        if (!outcome.outcomes().isEmpty()
                && outcome.outcomes().stream().noneMatch(item -> item.result().success())) {
            propagateTerminalFailure(outcome.outcomes());
        }
        SearchResponse result = outcome.response();
        List<SongMatch> matches = result.groups().stream().flatMap(group -> group.sources().stream())
                .map(track -> new SongMatch(track, matcher.score(song, track)))
                .sorted(java.util.Comparator.comparingDouble(SongMatch::score).reversed()).limit(30).toList();
        matches.forEach(match -> storage.saveMatch(songId, match.track(), match.score()));
        return matches;
    }

    private static void propagateTerminalFailure(List<ProviderOutcome> outcomes) {
        RuntimeException rateLimited = null;
        RuntimeException stateUnavailable = null;
        RuntimeException firstFailure = null;
        for (ProviderOutcome outcome : outcomes) {
            RuntimeException failure = outcome.failure();
            if (failure == null) continue;
            if (firstFailure == null) firstFailure = failure;
            if (failure instanceof ProviderRateLimitedException && rateLimited == null) {
                rateLimited = failure;
            } else if (failure instanceof ProviderRateStateUnavailableException && stateUnavailable == null) {
                stateUnavailable = failure;
            }
        }
        if (rateLimited != null) throw rateLimited;
        if (stateUnavailable != null) throw stateUnavailable;
        if (firstFailure != null) throw firstFailure;
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
                long started = System.nanoTime();
                try {
                    if (provider.supportsSongMetadata() && providers.containsKey(provider)) {
                        MusicMetadataProvider.ProviderHealth health = providers.get(provider).health(Duration.ofSeconds(config.timeoutSeconds()));
                        if (health.healthy()) {
                            configService.recordSuccess(provider);
                        } else {
                            configService.recordError(provider, health.error());
                        }
                        return new TestResult(provider, provider.displayName(), health.healthy(), health.latencyMs(), health.error());
                    }
                    ArtistMetadataProvider artistProvider = artistProviders.get(provider);
                    if (artistProvider == null) {
                        String msg = "该平台适配器未注册";
                        configService.recordError(provider, msg);
                        return new TestResult(provider, provider.displayName(), false, 0, msg);
                    }
                    List<ExternalArtist> artists = artistProvider.search("周杰伦", 1, Duration.ofSeconds(config.timeoutSeconds()));
                    long latency = elapsed(started);
                    boolean healthy = artists != null && !artists.isEmpty();
                    if (healthy) {
                        configService.recordSuccess(provider);
                        return new TestResult(provider, provider.displayName(), true, latency, null);
                    }
                    String err = "未返回歌手头像候选";
                    configService.recordError(provider, err);
                    return new TestResult(provider, provider.displayName(), false, latency, err);
                } catch (RuntimeException ex) {
                    String error = publicError(ex); configService.recordError(provider, error);
                    return new TestResult(provider, provider.displayName(), false, 0, error);
                }
            }, executor)).toList();
            futures.forEach(future -> results.add(future.join()));
        }
        return results;
    }

    public List<ArtistAvatarFallbackChain.FallbackTestResult> testFallback(Collection<AvatarFallbackProvider> requested) {
        if (fallbackChain == null) return List.of();
        MusicSourceConfig config = configService.getConfig();
        Duration timeout = Duration.ofSeconds(config != null ? Math.max(2, config.timeoutSeconds()) : 5);
        return fallbackChain.test(requested, timeout);
    }

    private ProviderOutcome searchProvider(MusicProvider provider, String query, MusicSourceConfig config, boolean refresh) {
        long started = System.nanoTime();
        try {
            if (!refresh) {
                var cached = storage.cachedSearch(provider, query, config.resultLimit());
                if (cached.isPresent()) {
                    return new ProviderOutcome(
                            new ProviderResult(provider, provider.displayName(), true, true, cached.get(), null, 0),
                            null);
                }
            }
            List<ExternalTrack> tracks = providers.get(provider).search(query, config.resultLimit(), Duration.ofSeconds(config.timeoutSeconds()));
            storage.saveSearch(provider, query, config.resultLimit(), tracks, config.searchCacheHours());
            configService.recordSuccess(provider);
            return new ProviderOutcome(
                    new ProviderResult(provider, provider.displayName(), true, false, tracks, null, elapsed(started)),
                    null);
        } catch (RuntimeException ex) {
            String error = publicError(ex); configService.recordError(provider, error);
            return new ProviderOutcome(
                    new ProviderResult(provider, provider.displayName(), false, false, List.of(), error, elapsed(started)),
                    ex);
        }
    }

    private List<TrackGroup> group(List<ProviderResult> results) {
        Map<String, List<ExternalTrack>> grouped = new LinkedHashMap<>();
        results.stream().flatMap(result -> result.tracks().stream())
                .forEach(track -> grouped.computeIfAbsent(matcher.groupKey(track), ignored -> new ArrayList<>()).add(track));
        Map<String, List<Song>> candidatesByLowerTitle = new java.util.HashMap<>();
        return grouped.values().stream().map(tracks -> {
            ExternalTrack representative = tracks.getFirst();
            String titleKey = representative.title() == null ? "" : representative.title().trim().toLowerCase(java.util.Locale.ROOT);
            List<Song> candidates = titleKey.isEmpty() ? List.of() : candidatesByLowerTitle.computeIfAbsent(titleKey,
                    ignored -> {
                        List<Song> found = songRepository.findTop10ByTitleIgnoreCase(representative.title());
                        return found == null ? List.of() : found;
                    });
            List<LocalMatch> local = candidates.stream()
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

    private record ProviderOutcome(ProviderResult result, RuntimeException failure) {}
    private record SearchOutcome(SearchResponse response, List<ProviderOutcome> outcomes) {}

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
