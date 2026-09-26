package com.homektv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFallbackRecoveryPolicyTest {
    @Test
    fun onlyExplicitMediaFormatFailuresMayEscalateToLiveTranscode() {
        assertTrue(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, -1007)) // malformed
        assertTrue(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, -1010)) // unsupported
        assertTrue(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, 200)) // not valid for progressive playback

        assertFalse(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, -1004)) // I/O
        assertFalse(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, -110)) // timeout
        assertFalse(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(100, 0)) // server died
        assertFalse(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(1, 0)) // unknown
        assertFalse(FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(null, null)) // synchronous setup failure
    }

    @Test
    fun media3DecoderFailureMakesFallbackUnknownErrorSafeToEscalate() {
        assertTrue(
            FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(
                what = Int.MIN_VALUE,
                extra = 0,
                precededByMedia3DecoderFailure = true,
            ),
        )
        assertFalse(
            FallbackPlaybackErrorPolicy.shouldRequestLiveTranscode(
                what = Int.MIN_VALUE,
                extra = 0,
                precededByMedia3DecoderFailure = false,
            ),
        )
    }

    @Test
    fun liveTranscodeSeekMustReopenPipeForEitherActivePlaybackEngine() {
        assertTrue(
            PlaybackSeekPolicy.shouldReopenLivePipe(
                isLiveTranscoded = true,
                engineType = PlaybackEngineType.PRIMARY_MEDIA3,
            ),
        )
        assertTrue(
            PlaybackSeekPolicy.shouldReopenLivePipe(
                isLiveTranscoded = true,
                engineType = PlaybackEngineType.FALLBACK_FFMPEG,
            ),
        )
        assertFalse(
            PlaybackSeekPolicy.shouldReopenLivePipe(
                isLiveTranscoded = false,
                engineType = PlaybackEngineType.PRIMARY_MEDIA3,
            ),
        )
        assertFalse(
            PlaybackSeekPolicy.shouldReopenLivePipe(
                isLiveTranscoded = true,
                engineType = PlaybackEngineType.RESOLVE_REQUIRED,
            ),
        )
    }
}
