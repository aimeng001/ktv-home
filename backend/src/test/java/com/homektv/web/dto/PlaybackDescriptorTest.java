package com.homektv.web.dto;

import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
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
    void variantDescriptorsRepresentPreparingReadyAndFailedStates() {
        SongFile source = source();
        PlaybackVariant variant = new PlaybackVariant(42L, "source-hash", PlaybackVariantProfile.H264_AAC_MP4_V1);
        variant.setId(77L);
        variant.markReady("/data/cache/77.mp4", 1024L);

        PlaybackDescriptor ready = PlaybackDescriptor.ready(source, variant);
        variant.markPreparing("worker", java.time.OffsetDateTime.now().plusMinutes(1));
        PlaybackDescriptor preparing = PlaybackDescriptor.preparing(source, variant);
        variant.markFailed("ENCODE_FAILED", "synthetic failure");
        PlaybackDescriptor failed = PlaybackDescriptor.failed(source, variant);

        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.variantId()).isEqualTo(77L);
        assertThat(ready.streamUrl()).isEqualTo("/api/playback/stream/77");
        assertThat(ready.errorCode()).isNull();
        assertThat(preparing.status()).isEqualTo("PREPARING");
        assertThat(preparing.streamUrl()).isNull();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("ENCODE_FAILED");
        assertThat(failed.errorMessage()).isEqualTo("synthetic failure");
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
