package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDecodeRecoveryPolicyTest {
    @Test
    fun onlyAnUnresolvedVideoSourceCanEscalateOnce() {
        assertTrue(
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = true,
                isAlreadyLiveTranscoded = false,
                fileId = 11L,
                requestedFileId = null,
            ),
        )
        assertFalse(
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = true,
                isAlreadyLiveTranscoded = false,
                fileId = 11L,
                requestedFileId = 11L,
            ),
        )
        assertFalse(
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = true,
                isAlreadyLiveTranscoded = true,
                fileId = 11L,
                requestedFileId = null,
            ),
        )
        assertFalse(
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = false,
                isAlreadyLiveTranscoded = false,
                fileId = 11L,
                requestedFileId = null,
            ),
        )
        assertFalse(
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = true,
                isAlreadyLiveTranscoded = false,
                fileId = null,
                requestedFileId = null,
            ),
        )
    }

    @Test
    fun videoStallNeverInPlaceSeeksLivePipeAndExhaustionEscalatesToLiveTranscodeFirst() {
        assertEquals(
            PlaybackStallAction.IN_PLACE_SEEK,
            PlaybackStallRecoveryPolicy.resolve(
                decision = VideoPlaybackWatchdog.Decision.RECOVER,
                isVideo = true,
                isLiveTranscode = false,
                fileId = 11L,
                requestedFileId = null,
            ),
        )
        assertEquals(
            PlaybackStallAction.SKIP_SEEK_FOR_LIVE_PIPE,
            PlaybackStallRecoveryPolicy.resolve(
                decision = VideoPlaybackWatchdog.Decision.RECOVER,
                isVideo = true,
                isLiveTranscode = true,
                fileId = 11L,
                requestedFileId = 11L,
            ),
        )
        assertEquals(
            PlaybackStallAction.REQUEST_LIVE_TRANSCODE,
            PlaybackStallRecoveryPolicy.resolve(
                decision = VideoPlaybackWatchdog.Decision.EXHAUSTED,
                isVideo = true,
                isLiveTranscode = false,
                fileId = 11L,
                requestedFileId = null,
            ),
        )
        assertEquals(
            PlaybackStallAction.REPORT_ERROR,
            PlaybackStallRecoveryPolicy.resolve(
                decision = VideoPlaybackWatchdog.Decision.EXHAUSTED,
                isVideo = true,
                isLiveTranscode = true,
                fileId = 11L,
                requestedFileId = 11L,
            ),
        )
    }

    @Test
    fun transientRetryAndReadTimeoutProvideBoundedHeadroomForNasAndPipeStartup() {
        assertTrue(PlaybackTransientRetryPolicy.STREAM_READ_TIMEOUT_SECONDS >= 20L)
        assertEquals(4, PlaybackTransientRetryPolicy.maxRetries)
        assertEquals(750L, PlaybackTransientRetryPolicy.delayForAttempt(0))
        assertEquals(1_500L, PlaybackTransientRetryPolicy.delayForAttempt(1))
        assertEquals(2_500L, PlaybackTransientRetryPolicy.delayForAttempt(2))
        assertEquals(3_500L, PlaybackTransientRetryPolicy.delayForAttempt(3))
        assertEquals(3_500L, PlaybackTransientRetryPolicy.delayForAttempt(10))
    }
}
