package com.homektv.playback;

import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.domain.PlaybackVariantStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackVariantTest {

    @Test
    void onlyReadyVariantCanBeServed() {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);

        assertThat(variant.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
        assertThat(variant.isReady()).isFalse();

        variant.markReady("playback-cache/42.mp4", 1234L);

        assertThat(variant.isReady()).isTrue();
        assertThat(variant.getCachePath()).isEqualTo("playback-cache/42.mp4");
        assertThat(variant.getFileSize()).isEqualTo(1234L);
    }

    @Test
    void streamLeaseKeepsReadyVariantPinnedUntilExpiry() {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        variant.markReady("42.mp4", 1234L);

        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        variant.touchAccess(now, now.plusMinutes(10));

        assertThat(variant.getLastAccessAt()).isEqualTo(now);
        assertThat(variant.hasActiveStreamLease(now.plusMinutes(1))).isTrue();
        assertThat(variant.hasActiveStreamLease(now.plusMinutes(11))).isFalse();
    }
    @Test
    void failedVariantCarriesTypedErrorAndCannotBeServed() {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);

        variant.markFailed("TRANSCODE_FAILED", "ffmpeg exit=1");

        assertThat(variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(variant.isReady()).isFalse();
        assertThat(variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(variant.getErrorMessage()).isEqualTo("ffmpeg exit=1");
    }
}
