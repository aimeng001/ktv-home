package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioLayoutResolverTest {

    private final AudioLayoutResolver resolver = new AudioLayoutResolver();

    @Test
    void externalDualChannelDefaultUsesDualChannelForOneTrack() {
        var result = resolver.resolve(
                LibraryMode.EXTERNAL_READ_ONLY,
                AudioLayoutSource.AUTO_DEFAULT,
                AudioLayout.NORMAL_STEREO,
                1,
                AudioLayout.DUAL_CHANNEL);

        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(result.source()).isEqualTo(AudioLayoutSource.AUTO_DEFAULT);
    }

    @Test
    void externalDualChannelDefaultUsesDualTrackForIndependentTracks() {
        var result = resolver.resolve(
                LibraryMode.EXTERNAL_READ_ONLY,
                AudioLayoutSource.AUTO_DEFAULT,
                AudioLayout.DUAL_CHANNEL,
                2,
                AudioLayout.DUAL_CHANNEL);

        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_TRACK);
    }

    @Test
    void externalDualTrackDefaultFallsBackForOneTrack() {
        var result = resolver.resolve(
                LibraryMode.EXTERNAL_READ_ONLY,
                AudioLayoutSource.AUTO_DEFAULT,
                AudioLayout.DUAL_TRACK,
                1,
                AudioLayout.DUAL_TRACK);

        assertThat(result.layout()).isEqualTo(AudioLayout.NORMAL_STEREO);
    }

    @Test
    void manualNormalStereoSurvivesChangedExternalDefault() {
        var result = resolver.resolve(
                LibraryMode.EXTERNAL_READ_ONLY,
                AudioLayoutSource.MANUAL,
                AudioLayout.NORMAL_STEREO,
                1,
                AudioLayout.DUAL_CHANNEL);

        assertThat(result.layout()).isEqualTo(AudioLayout.NORMAL_STEREO);
    }

    @Test
    void legacyLayoutIsNotOverwrittenByGlobalDefault() {
        var result = resolver.resolve(
                LibraryMode.EXTERNAL_READ_ONLY,
                AudioLayoutSource.LEGACY,
                AudioLayout.DUAL_CHANNEL,
                1,
                AudioLayout.NORMAL_STEREO);

        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
    }

    @Test
    void managedTwoTrackMediaKeepsTheExistingDualTrackRule() {
        var result = resolver.resolve(
                LibraryMode.MANAGED,
                AudioLayoutSource.AUTO_DEFAULT,
                AudioLayout.NORMAL_STEREO,
                2,
                AudioLayout.DUAL_CHANNEL);

        assertThat(result.layout()).isEqualTo(AudioLayout.DUAL_TRACK);
    }
}
