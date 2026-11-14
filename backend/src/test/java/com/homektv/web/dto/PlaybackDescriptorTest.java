package com.homektv.web.dto;

import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackDescriptorTest {

    @Test
    void idleAndNullLayoutUseThePlatformNeutralStereoDefaults() {
        PlaybackDescriptor idle = PlaybackDescriptor.idle();
        PlaybackDescriptor constructed = new PlaybackDescriptor("NONE", "IDLE", null, null,
                null, 0, null, null, null, null);

        assertThat(idle.kind()).isEqualTo("NONE");
        assertThat(idle.status()).isEqualTo("IDLE");
        assertStereo(idle.audioLayout());
        assertStereo(constructed.audioLayout());
    }

    @Test
    void nativeAndLiveDescriptorsKeepTheSelectedSourceAndAudioSemantics() {
        SongFile source = source();

        PlaybackDescriptor nativeSource = PlaybackDescriptor.nativeSource(source);
        PlaybackDescriptor live = PlaybackDescriptor.liveTranscode(source);

        assertThat(nativeSource.kind()).isEqualTo("NATIVE");
        assertThat(nativeSource.status()).isEqualTo("READY");
        assertThat(nativeSource.sourceFileId()).isEqualTo(42L);
        assertThat(nativeSource.streamUrl()).isEqualTo("/api/stream/42");
        assertThat(nativeSource.audioTracks()).isEqualTo(3);
        assertThat(nativeSource.vocalTrackIndex()).isEqualTo(2);
        assertDualTrack(nativeSource.audioLayout());

        assertThat(live.kind()).isEqualTo("LIVE_TRANSCODE");
        assertThat(live.status()).isEqualTo("READY");
        assertThat(live.streamUrl()).isEqualTo("/api/stream/42?transcode=true");
        assertThat(live.audioTracks()).isEqualTo(3);
        assertDualTrack(live.audioLayout());
    }

    @Test
    void failedDescriptorSupportsBothKnownSourceAndMissingSource() {
        PlaybackDescriptor withSource = PlaybackDescriptor.failed(source(), "SOURCE_MISSING", "missing");
        PlaybackDescriptor withoutSource = PlaybackDescriptor.failed(null, "NO_SOURCE", "unavailable");

        assertThat(withSource.sourceFileId()).isEqualTo(42L);
        assertThat(withSource.audioTracks()).isEqualTo(3);
        assertDualTrack(withSource.audioLayout());

        assertThat(withoutSource.sourceFileId()).isNull();
        assertThat(withoutSource.audioTracks()).isZero();
        assertThat(withoutSource.vocalTrackIndex()).isNull();
        assertThat(withoutSource.errorCode()).isEqualTo("NO_SOURCE");
        assertStereo(withoutSource.audioLayout());
    }

    private static SongFile source() {
        SongFile file = new SongFile();
        file.setId(42L);
        file.setAudioTracks(3);
        file.setVocalTrackIndex(2);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setOriginalTrackIndex(0);
        file.setAccompanimentTrackIndex(2);
        file.setOriginalChannel(AudioChannel.RIGHT);
        file.setAccompanimentChannel(AudioChannel.LEFT);
        return file;
    }

    private static void assertDualTrack(AudioLayoutDto layout) {
        assertThat(layout.layout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(layout.originalTrackIndex()).isEqualTo(0);
        assertThat(layout.accompanimentTrackIndex()).isEqualTo(2);
        assertThat(layout.originalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(layout.accompanimentChannel()).isEqualTo(AudioChannel.LEFT);
    }

    private static void assertStereo(AudioLayoutDto layout) {
        assertThat(layout.layout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        assertThat(layout.originalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(layout.accompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
    }
}
