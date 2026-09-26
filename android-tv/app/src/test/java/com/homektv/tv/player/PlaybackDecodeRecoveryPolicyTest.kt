package com.homektv.tv.player

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
    }
}
