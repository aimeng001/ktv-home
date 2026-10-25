package com.homektv.musicsource;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicSourceSearchServiceTest {

    @Test
    void usesConfiguredProvidersWhenCallerDoesNotOverrideThem() {
        Set<MusicProvider> selected = MusicSourceSearchService.selectProviders(Set.of(), Set.of(MusicProvider.QQ));

        selected.retainAll(Set.of(MusicProvider.QQ));

        assertThat(selected).containsExactly(MusicProvider.QQ);
    }

    @Test
    void rejectsInvalidQueriesDisabledServicesAndUnconfiguredProviders() {
        Fixture enabled = fixture(config(true, Set.of(MusicProvider.QQ)));
        assertApiError(() -> enabled.service.search(null, null, false), "MUSIC_SOURCE_QUERY_INVALID");
        assertApiError(() -> enabled.service.search(" x ", null, false), "MUSIC_SOURCE_QUERY_INVALID");

        Fixture disabled = fixture(config(false, Set.of(MusicProvider.QQ)));
        assertApiError(() -> disabled.service.search("晴天", null, false), "MUSIC_SOURCES_NOT_CONFIGURED");

        Fixture noProviders = fixture(config(true, Set.of()));
        assertApiError(() -> noProviders.service.search("晴天", null, false), "MUSIC_SOURCES_NOT_CONFIGURED");

        assertApiError(() -> enabled.service.search("晴天", Set.of(MusicProvider.NETEASE), false),
                "MUSIC_SOURCES_NOT_CONFIGURED");
        verify(enabled.provider, never()).search(anyString(), anyInt(), eq(Duration.ofSeconds(5)));
        verifyNoInteractions(enabled.storage);
    }

    @Test
    void servesCachedSearchAndBuildsAllLocalMatchStates() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        ExternalTrack missing = track("missing", "晴天");
        ExternalTrack possible = track("possible", "夜曲");
        ExternalTrack exists = track("exists", "稻香");
        List<ExternalTrack> tracks = List.of(missing, possible, exists);
        when(fixture.storage.cachedSearch(MusicProvider.QQ, "晴天", 20)).thenReturn(Optional.of(tracks));
        when(fixture.matcher.groupKey(missing)).thenReturn("missing");
        when(fixture.matcher.groupKey(possible)).thenReturn("possible");
        when(fixture.matcher.groupKey(exists)).thenReturn("exists");
        Song belowThreshold = song(1, "晴天", "周杰伦");
        Song candidate = song(2, "夜曲", "周杰伦");
        Song exact = song(3, "稻香", "周杰伦");
        when(fixture.songs.findTop10ByTitleIgnoreCase("晴天")).thenReturn(List.of(belowThreshold));
        when(fixture.songs.findTop10ByTitleIgnoreCase("夜曲")).thenReturn(List.of(candidate));
        when(fixture.songs.findTop10ByTitleIgnoreCase("稻香")).thenReturn(List.of(exact));
        when(fixture.matcher.score(belowThreshold, missing)).thenReturn(0.71);
        when(fixture.matcher.score(candidate, possible)).thenReturn(0.8);
        when(fixture.matcher.score(exact, exists)).thenReturn(0.95);

        MusicSourceSearchService.SearchResponse response = fixture.service.search(" 晴天 ", Set.of(), false);

        assertThat(response.query()).isEqualTo("晴天");
        assertThat(response.providers()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.cached()).isTrue();
            assertThat(result.tracks()).containsExactlyElementsOf(tracks);
        });
        assertThat(response.groups()).extracting(MusicSourceSearchService.TrackGroup::localState)
                .containsExactly("LOCAL_MISSING", "POSSIBLE_MATCH", "LOCAL_EXISTS");
        assertThat(response.groups().get(0).localMatches()).isEmpty();
        assertThat(response.groups().get(1).localMatches()).extracting(MusicSourceSearchService.LocalMatch::score)
                .containsExactly(0.8);
        assertThat(response.groups().get(2).localMatches()).extracting(MusicSourceSearchService.LocalMatch::songId)
                .containsExactly(3L);
        verify(fixture.provider, never()).search(anyString(), anyInt(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void fetchesAndCachesProviderResultsAndReturnsEmptyGroupsForNoTracks() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        when(fixture.provider.search("周杰伦", 20, Duration.ofSeconds(5))).thenReturn(List.of());

        MusicSourceSearchService.SearchResponse response = fixture.service.search("周杰伦", null, true);

        assertThat(response.groups()).isEmpty();
        assertThat(response.providers()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.cached()).isFalse();
            assertThat(result.tracks()).isEmpty();
        });
        verify(fixture.storage).saveSearch(MusicProvider.QQ, "周杰伦", 20, List.of(), 6);
        verify(fixture.configService).recordSuccess(MusicProvider.QQ);
    }

    @Test
    void recordsProviderSearchErrorsWithoutFailingTheWholeSearch() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        when(fixture.provider.search("周杰伦", 20, Duration.ofSeconds(5)))
                .thenThrow(new IllegalStateException("upstream unavailable"));

        MusicSourceSearchService.SearchResponse response = fixture.service.search("周杰伦", null, true);

        assertThat(response.groups()).isEmpty();
        assertThat(response.providers()).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.cached()).isFalse();
            assertThat(result.tracks()).isEmpty();
            assertThat(result.error()).isEqualTo("upstream unavailable");
        });
        verify(fixture.configService).recordError(MusicProvider.QQ, "upstream unavailable");
        verify(fixture.storage, never()).saveSearch(any(), anyString(), anyInt(), org.mockito.ArgumentMatchers.anyList(), anyInt());
    }

    @Test
    void detailValidatesIdsUsesFreshCacheAndFetchesWhenRequested() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        ExternalTrack cached = track("cache-id", "晴天");
        when(fixture.storage.track(MusicProvider.QQ, "cache-id", true)).thenReturn(Optional.of(cached));

        assertApiError(() -> fixture.service.detail(MusicProvider.QQ, "  ", false), "EXTERNAL_TRACK_ID_INVALID");
        assertThat(fixture.service.detail(MusicProvider.QQ, " cache-id ", false)).isEqualTo(cached);
        verify(fixture.provider, never()).detail(anyString(), eq(Duration.ofSeconds(5)));

        ExternalTrack fetched = track("refresh-id", "夜曲");
        when(fixture.provider.detail("refresh-id", Duration.ofSeconds(5))).thenReturn(fetched);
        assertThat(fixture.service.detail(MusicProvider.QQ, "refresh-id", true)).isEqualTo(fetched);
        verify(fixture.storage).upsertTrack(eq(fetched), org.mockito.ArgumentMatchers.any());
        verify(fixture.configService).recordSuccess(MusicProvider.QQ);
    }

    @Test
    void detailFetchesFromProviderWhenFreshCacheMisses() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        ExternalTrack fetched = track("uncached-id", "夜曲");
        when(fixture.storage.track(MusicProvider.QQ, "uncached-id", true)).thenReturn(Optional.empty());
        when(fixture.provider.detail("uncached-id", Duration.ofSeconds(5))).thenReturn(fetched);

        assertThat(fixture.service.detail(MusicProvider.QQ, "uncached-id", false)).isEqualTo(fetched);

        verify(fixture.storage).upsertTrack(eq(fetched), org.mockito.ArgumentMatchers.any());
        verify(fixture.configService).recordSuccess(MusicProvider.QQ);
    }

    @Test
    void detailRejectsDisabledProvidersBeforeCallingTheProvider() {
        Fixture fixture = fixture(config(false, Set.of(MusicProvider.QQ)));
        assertApiError(() -> fixture.service.detail(MusicProvider.QQ, "track-id", true), "MUSIC_PROVIDER_DISABLED");
        Fixture notSelected = fixture(config(true, Set.of(MusicProvider.NETEASE)));
        assertApiError(() -> notSelected.service.detail(MusicProvider.QQ, "track-id", true), "MUSIC_PROVIDER_DISABLED");
        verify(fixture.provider, never()).detail(anyString(), eq(Duration.ofSeconds(5)));
        verify(notSelected.provider, never()).detail(anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void matchesUseSongTextByDefaultSortCandidatesAndSaveEveryMatch() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        Song local = song(17, "晴天", "周杰伦");
        ExternalTrack lower = track("lower", "晴天");
        ExternalTrack higher = track("higher", "晴天");
        when(fixture.songs.findById(17L)).thenReturn(Optional.of(local));
        when(fixture.storage.cachedSearch(MusicProvider.QQ, "晴天 周杰伦", 20)).thenReturn(Optional.of(List.of(lower, higher)));
        when(fixture.matcher.groupKey(lower)).thenReturn("lower");
        when(fixture.matcher.groupKey(higher)).thenReturn("higher");
        when(fixture.songs.findTop10ByTitleIgnoreCase("晴天")).thenReturn(List.of());
        when(fixture.matcher.score(local, lower)).thenReturn(0.8);
        when(fixture.matcher.score(local, higher)).thenReturn(0.99);

        List<MusicSourceSearchService.SongMatch> matches = fixture.service.matches(17L, false);

        assertThat(matches).extracting(match -> match.track().externalId()).containsExactly("higher", "lower");
        verify(fixture.storage).saveMatch(17L, higher, 0.99);
        verify(fixture.storage).saveMatch(17L, lower, 0.8);
        assertApiError(() -> fixture.service.matches(404L, false), "SONG_NOT_FOUND");
    }

    @Test
    void matchesUseSongTextForNullKeywordsAndPreserveExplicitKeywords() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        Song local = song(18, "晴天", "周杰伦");
        when(fixture.songs.findById(18L)).thenReturn(Optional.of(local));
        when(fixture.storage.cachedSearch(MusicProvider.QQ, "晴天 周杰伦", 20)).thenReturn(Optional.of(List.of()));
        when(fixture.storage.cachedSearch(MusicProvider.QQ, "手动关键词", 20)).thenReturn(Optional.of(List.of()));

        assertThat(fixture.service.matches(18L, null, Set.of(), false)).isEmpty();
        assertThat(fixture.service.matches(18L, "手动关键词", Set.of(MusicProvider.QQ), false)).isEmpty();

        verify(fixture.storage).cachedSearch(MusicProvider.QQ, "晴天 周杰伦", 20);
        verify(fixture.storage).cachedSearch(MusicProvider.QQ, "手动关键词", 20);
    }

    @Test
    void validatesBatchIdsDeduplicatesAndReturnsPerSongFailures() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        assertApiError(() -> fixture.service.batchMatches(null), "MUSIC_SOURCE_BATCH_INVALID");
        assertApiError(() -> fixture.service.batchMatches(Arrays.asList(null, 0L, -1L)), "MUSIC_SOURCE_BATCH_INVALID");
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= 51; id++) tooMany.add(id);
        assertApiError(() -> fixture.service.batchMatches(tooMany), "MUSIC_SOURCE_BATCH_INVALID");

        when(fixture.songs.findById(9L)).thenReturn(Optional.empty());
        MusicSourceSearchService.BatchMatchResponse response = fixture.service.batchMatches(Arrays.asList(9L, 9L, 0L));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.matched()).isZero();
        assertThat(response.songs()).singleElement().satisfies(item -> {
            assertThat(item.songId()).isEqualTo(9L);
            assertThat(item.error()).isEqualTo("歌曲不存在");
        });
    }

    @Test
    void batchMatchesConvertPerSongRuntimeFailuresToPublicErrors() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        Song song = song(5, "晴天", "周杰伦");
        AtomicInteger lookups = new AtomicInteger();
        when(fixture.songs.findById(5L)).thenAnswer(invocation -> {
            if (lookups.incrementAndGet() == 1) return Optional.of(song);
            throw new IllegalStateException("repository unavailable");
        });

        MusicSourceSearchService.BatchMatchResponse response = fixture.service.batchMatches(List.of(5L));

        assertThat(response.songs()).singleElement().satisfies(item -> {
            assertThat(item.matches()).isEmpty();
            assertThat(item.error()).isEqualTo("repository unavailable");
        });
    }

    @Test
    void batchMatchesCountSongsWithCandidates() {
        Fixture fixture = fixture(config(true, Set.of(MusicProvider.QQ)));
        Song song = song(7, "晴天", "周杰伦");
        ExternalTrack candidate = track("candidate", "晴天");
        when(fixture.songs.findById(7L)).thenReturn(Optional.of(song));
        when(fixture.storage.cachedSearch(MusicProvider.QQ, "晴天 周杰伦", 20)).thenReturn(Optional.of(List.of(candidate)));
        when(fixture.matcher.groupKey(candidate)).thenReturn("candidate");
        when(fixture.songs.findTop10ByTitleIgnoreCase("晴天")).thenReturn(List.of());
        when(fixture.matcher.score(song, candidate)).thenReturn(0.99);

        MusicSourceSearchService.BatchMatchResponse response = fixture.service.batchMatches(List.of(7L));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.matched()).isEqualTo(1);
        assertThat(response.songs()).singleElement().satisfies(item -> {
            assertThat(item.matches()).extracting(match -> match.track().externalId()).containsExactly("candidate");
            assertThat(item.error()).isNull();
        });
    }

    @Test
    void batchMatchesRejectWhenSearchIsDisabledOrNoProviderIsConfigured() {
        Fixture disabled = fixture(config(false, Set.of(MusicProvider.QQ)));
        assertApiError(() -> disabled.service.batchMatches(List.of(1L)), "MUSIC_SOURCES_NOT_CONFIGURED");
        Fixture noProvider = fixture(config(true, Set.of()));
        assertApiError(() -> noProvider.service.batchMatches(List.of(1L)), "MUSIC_SOURCES_NOT_CONFIGURED");
        verifyNoInteractions(disabled.songs, noProvider.songs);
    }

    @Test
    void testsConfiguredProvidersAndRecordsHealthFailures() {
        Fixture healthy = fixture(config(true, Set.of(MusicProvider.QQ)));
        when(healthy.provider.health(Duration.ofSeconds(5)))
                .thenReturn(new MusicMetadataProvider.ProviderHealth(MusicProvider.QQ, true, 12, null));
        List<MusicSourceSearchService.TestResult> good = healthy.service.test(null);
        assertThat(good).singleElement().satisfies(result -> {
            assertThat(result.healthy()).isTrue();
            assertThat(result.latencyMs()).isEqualTo(12);
            assertThat(result.error()).isNull();
        });
        assertThat(healthy.service.test(Set.of(MusicProvider.QQ))).hasSize(1);
        verify(healthy.configService, times(2)).recordSuccess(MusicProvider.QQ);

        Fixture unhealthy = fixture(config(true, Set.of(MusicProvider.QQ)));
        when(unhealthy.provider.health(Duration.ofSeconds(5))).thenThrow(new IllegalStateException("provider offline"));
        List<MusicSourceSearchService.TestResult> bad = unhealthy.service.test(Set.of());
        assertThat(bad).singleElement().satisfies(result -> {
            assertThat(result.healthy()).isFalse();
            assertThat(result.latencyMs()).isZero();
            assertThat(result.error()).isEqualTo("provider offline");
        });
        verify(unhealthy.configService).recordError(MusicProvider.QQ, "provider offline");
    }

    @Test
    void searchFetchesWhenCacheIsEmptyAndUsesSafeMessageForMessageLessFailures() {
        Fixture success = fixture(config(true, Set.of(MusicProvider.QQ)));
        ExternalTrack track = track("network-result", "晴天");
        when(success.provider.search("周杰伦", 20, Duration.ofSeconds(5))).thenReturn(List.of(track));
        when(success.matcher.groupKey(track)).thenReturn("network-result");
        when(success.songs.findTop10ByTitleIgnoreCase("晴天")).thenReturn(List.of());

        assertThat(success.service.search("周杰伦", null, false).providers()).singleElement()
                .satisfies(result -> assertThat(result.cached()).isFalse());
        verify(success.storage).saveSearch(MusicProvider.QQ, "周杰伦", 20, List.of(track), 6);

        Fixture failure = fixture(config(true, Set.of(MusicProvider.QQ)));
        when(failure.provider.search("周杰伦", 20, Duration.ofSeconds(5)))
                .thenThrow(new IllegalStateException());
        assertThat(failure.service.search("周杰伦", null, true).providers()).singleElement()
                .satisfies(result -> assertThat(result.error()).isEqualTo("平台暂时不可用"));
    }

    private static Fixture fixture(MusicSourceConfig config) {
        MusicSourceConfigService configService = mock(MusicSourceConfigService.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        ExternalTrackMatcher matcher = mock(ExternalTrackMatcher.class);
        SongRepository songs = mock(SongRepository.class);
        MusicMetadataProvider provider = mock(MusicMetadataProvider.class);
        when(configService.getConfig()).thenReturn(config);
        when(provider.provider()).thenReturn(MusicProvider.QQ);
        when(storage.cachedSearch(any(), anyString(), anyInt())).thenReturn(Optional.empty());
        MusicSourceSearchService service = new MusicSourceSearchService(
                List.of(provider), configService, storage, matcher, songs);
        return new Fixture(service, configService, storage, matcher, songs, provider);
    }

    private static MusicSourceConfig config(boolean enabled, Set<MusicProvider> providers) {
        return new MusicSourceConfig(enabled, providers, 20, 5, 6, 2, 1500, 0.95);
    }

    private static ExternalTrack track(String id, String title) {
        return new ExternalTrack(MusicProvider.QQ, id, title, List.of("周杰伦"), null, 240_000,
                null, List.of(), null, "AVAILABLE", null);
    }

    private static Song song(long id, String title, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        return song;
    }

    private static void assertApiError(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo(expectedCode));
    }

    private record Fixture(MusicSourceSearchService service, MusicSourceConfigService configService,
                           ExternalTrackStorage storage, ExternalTrackMatcher matcher,
                           SongRepository songs, MusicMetadataProvider provider) { }
}
