package com.homektv.web;

import com.homektv.library.ArtistAvatarJobService;
import com.homektv.musicsource.MusicMetadataApplyService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.musicsource.MusicSourceSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicSourceControllerTest {

    @Test
    void savesProviderConfigurationAndQueuesUnresolvedArtistProfiles() {
        Fixture fixture = fixture();
        Map<String, Object> view = Map.of("enabled", true);
        when(fixture.config.configView()).thenReturn(view);

        Map<String, Object> result = fixture.controller.saveConfig(
                new MusicSourceController.ConfigRequest(true, List.of("qq", "QQ"), 15, 8, 10, 2, 1800, 0.85));

        assertThat(result).isSameAs(view);
        verify(fixture.config).save(new MusicSourceConfig(true, Set.of(MusicProvider.QQ), 15, 8, 10, 2, 1800, 0.85));
        verify(fixture.avatarJobs).enqueueUnresolvedProfilesAsync();
    }

    @Test
    void testEndpointAcceptsMissingBodyAndParsesRequestedProviders() {
        Fixture fixture = fixture();
        when(fixture.config.states()).thenReturn(List.of(Map.of("provider", "QQ")));
        when(fixture.search.test(any())).thenReturn(List.of());

        assertThat(fixture.controller.test(null)).containsKeys("results", "providerStatus");
        assertThat(fixture.controller.test(new MusicSourceController.ProviderRequest(List.of("qq", "QQ"))))
                .containsEntry("results", List.of());

        verify(fixture.search).test(Set.of());
        verify(fixture.search).test(Set.of(MusicProvider.QQ));
    }

    @Test
    void songMatchEndpointHandlesAbsentAndExplicitProviderLists() {
        Fixture fixture = fixture();
        when(fixture.search.matches(eq(7L), eq("晴天"), any(), eq(true))).thenReturn(List.of());
        when(fixture.search.matches(eq(8L), eq("夜曲"), any(), eq(false))).thenReturn(List.of());

        assertThat(fixture.controller.songMatches(7L, true, "晴天", null)).containsEntry("songId", 7L);
        fixture.controller.songMatches(8L, false, "夜曲", List.of("NETEASE", "qq"));

        verify(fixture.search).matches(7L, "晴天", Set.of(), true);
        verify(fixture.search).matches(8L, "夜曲", Set.of(MusicProvider.NETEASE, MusicProvider.QQ), false);
    }

    @Test
    void validatesExternalIdsAndForwardsApplyAndManualRequests() {
        Fixture fixture = fixture();
        var request = new MusicMetadataApplyService.ApplyRequest(Set.of("title"));

        assertThatThrownBy(() -> fixture.controller.apply(2L, "QQ", "bad/id", request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EXTERNAL_TRACK_ID_INVALID"));
        assertThatThrownBy(() -> fixture.controller.apply(2L, "QQ", null, request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EXTERNAL_TRACK_ID_INVALID"));
        fixture.controller.apply(2L, "qq", "track-1:alt", request);
        fixture.controller.applyManual(2L, request);
        fixture.controller.batchMatches(new MusicSourceController.BatchMatchRequest(List.of(2L, 3L)));

        verify(fixture.apply).apply(2L, MusicProvider.QQ, "track-1:alt", request);
        verify(fixture.apply).applyManual(2L, request);
        verify(fixture.search).batchMatches(List.of(2L, 3L));
        verifyNoInteractions(fixture.avatarJobs);
    }

    private static Fixture fixture() {
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        MusicSourceSearchService search = mock(MusicSourceSearchService.class);
        MusicMetadataApplyService apply = mock(MusicMetadataApplyService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        return new Fixture(new MusicSourceController(config, search, apply, avatarJobs), config, search, apply, avatarJobs);
    }

    private record Fixture(MusicSourceController controller, MusicSourceConfigService config,
                           MusicSourceSearchService search, MusicMetadataApplyService apply,
                           ArtistAvatarJobService avatarJobs) { }
}
