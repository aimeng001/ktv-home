package com.homektv.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioLayoutTest {

    @Test
    void exposesAllSupportedLayouts() {
        assertThat(AudioLayout.values()).containsExactly(
                AudioLayout.NORMAL_STEREO,
                AudioLayout.DUAL_TRACK,
                AudioLayout.DUAL_CHANNEL);
    }

    @Test
    void newSongFileUsesSafeNormalStereoDefaults() {
        SongFile file = new SongFile();

        assertThat(file.getAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        assertThat(file.getOriginalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(file.getAccompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(file.getOriginalTrackIndex()).isNull();
        assertThat(file.getAccompanimentTrackIndex()).isNull();
    }

    @Test
    void dualChannelDefaultsToLeftOriginalAndRightAccompanimentAndCanSwap() {
        SongFile file = new SongFile();
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);

        assertThat(file.getOriginalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(file.getAccompanimentChannel()).isEqualTo(AudioChannel.RIGHT);

        file.swapOriginalAndAccompaniment();

        assertThat(file.getOriginalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(file.getAccompanimentChannel()).isEqualTo(AudioChannel.LEFT);
    }

    @Test
    void legacyVocalTrackIndexRemainsTheAccompanimentTrackSemantic() {
        SongFile file = new SongFile();
        file.setAudioTracks(2);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setVocalTrackIndex(1);

        assertThat(file.getAccompanimentTrackIndex()).isEqualTo(1);

        file.swapOriginalAndAccompaniment();

        assertThat(file.getOriginalTrackIndex()).isEqualTo(1);
        assertThat(file.getAccompanimentTrackIndex()).isEqualTo(0);
        assertThat(file.getVocalTrackIndex()).isEqualTo(0);
    }

    @Test
    void unknownStoredLayoutFallsBackToNormalStereo() {
        SongFile file = new SongFile();
        file.setAudioLayoutValue("future_layout");

        assertThat(file.getAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
    }

    @Test
    void invalidStoredChannelUsesTheSemanticSideDefault() {
        SongFile file = new SongFile();
        file.setOriginalChannelValue("future_channel");
        file.setAccompanimentChannelValue("future_channel");

        assertThat(file.getOriginalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(file.getAccompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
    }
}
