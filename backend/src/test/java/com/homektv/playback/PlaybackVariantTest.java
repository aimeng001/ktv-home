package com.homektv.playback;

import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.domain.PlaybackVariantStatus;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void constructorRequiresSourceIdentityAndProfile() {
        PlaybackVariantProfile profile = PlaybackVariantProfile.H264_AAC_MP4_V1;

        assertThatThrownBy(() -> new PlaybackVariant(null, "source-v1", profile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackVariant(42L, null, profile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackVariant(42L, "  ", profile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackVariant(42L, "source-v1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyStatusStillRequiresANonblankCachePath() throws Exception {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        variant.setStatus(PlaybackVariantStatus.READY);

        assertThat(variant.isReady()).isFalse();

        Field cachePath = PlaybackVariant.class.getDeclaredField("cachePath");
        cachePath.setAccessible(true);
        cachePath.set(variant, "  ");
        assertThat(variant.isReady()).isFalse();

        cachePath.set(variant, "playback-cache/42.mp4");
        assertThat(variant.isReady()).isTrue();
    }

    @Test
    void readyTransitionRequiresNonblankPathAndPositiveSize() {
        for (String cachePath : new String[]{null, "  "}) {
            PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
            assertThatThrownBy(() -> variant.markReady(cachePath, 1234L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(variant.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
        }

        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        assertThatThrownBy(() -> variant.markReady("playback-cache/42.mp4", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(variant.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
    }

    @Test
    void audioLayoutCopyAndTransitionsKeepTheirOwnTrackSemantics() {
        SongFile source = new SongFile();
        source.setAudioTracks(2);
        source.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        source.setOriginalChannel(AudioChannel.RIGHT);
        source.setAccompanimentChannel(AudioChannel.LEFT);
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);

        variant.copyAudioSemanticsFrom(source);
        variant.markReady("playback-cache/42.mp4", 1234L);
        variant.markFailed("TRANSCODE_FAILED", "failed");
        variant.touchAccess(OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(1));
        variant.markPreparing("worker", OffsetDateTime.now().plusMinutes(2));

        assertThat(variant.getAudioTracks()).isEqualTo(2);
        assertThat(variant.getAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(variant.getOriginalChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(variant.getAccompanimentChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(variant.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
        assertThat(variant.getLeaseOwner()).isEqualTo("worker");
        assertThat(variant.getLeaseUntil()).isAfter(OffsetDateTime.now());
        assertThat(variant.getErrorCode()).isNull();
        assertThat(variant.getErrorMessage()).isNull();
        assertThat(variant.getReadyAt()).isNull();
        assertThat(variant.getStreamLeaseUntil()).isNull();
    }

    @Test
    void streamLeaseKeepsReadyVariantPinnedUntilExpiry() {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        assertThat(variant.hasActiveStreamLease(java.time.OffsetDateTime.now())).isFalse();
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
