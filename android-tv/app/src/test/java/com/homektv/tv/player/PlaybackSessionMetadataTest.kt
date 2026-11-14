package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionMetadataTest {

    @Test
    fun reusingActivePrimaryPlaybackPreservesFailureAndRecoveryGuards() {
        val state = PlaybackSessionMetadata().apply {
            commitNewSource(
                queueId = 10L,
                fileId = 100L,
                streamUrl = "http://host/api/stream/100",
                hasVideoDeclared = true,
                format = "mkv",
                songDurationMs = 200_000L,
            )
            currentRequest = PlaybackRequestIdentity(queueId = 10L, fileId = 100L)
            videoFailureReportedFileId = 100L
            resolutionRequestedFileId = 100L
        }

        val outcome = state.preparePlay(
            queueId = 10L,
            fileId = 100L,
            streamUrl = "http://host/api/stream/100",
            hasVideoDeclared = true,
            format = "mkv",
            songDurationMs = 200_000L,
            engineChoice = PlaybackEngineType.PRIMARY_MEDIA3,
            activeEngineType = PlaybackEngineType.PRIMARY_MEDIA3,
            primaryPlaybackState = androidx.media3.common.Player.STATE_READY,
        )

        assertEquals(PlaybackPrepareAction.REUSE_ACTIVE, outcome.action)
        assertEquals(100L, state.videoFailureReportedFileId)
        assertEquals(100L, state.resolutionRequestedFileId)
        assertEquals(10L, state.currentQueueId)
        assertEquals(100L, state.currentFileId)
    }

    @Test
    fun earlyResolveRequiredUpdatesQueueAndFileIdsSoPreviousSongNeverLeaksIntoRecovery() {
        val state = PlaybackSessionMetadata().apply {
            commitNewSource(
                queueId = 10L,
                fileId = 100L,
                streamUrl = "http://host/api/stream/100?liveTranscode=1&start=15",
                hasVideoDeclared = true,
                format = "rmvb",
                songDurationMs = 180_000L,
            )
            currentRequest = PlaybackRequestIdentity(queueId = 10L, fileId = 100L)
            resolutionRequestedFileId = 100L
        }

        val outcome = state.preparePlay(
            queueId = 11L,
            fileId = 200L,
            streamUrl = "http://host/api/stream/200",
            hasVideoDeclared = true,
            format = "avi",
            songDurationMs = 240_000L,
            engineChoice = PlaybackEngineType.RESOLVE_REQUIRED,
            activeEngineType = PlaybackEngineType.PRIMARY_MEDIA3,
            primaryPlaybackState = androidx.media3.common.Player.STATE_READY,
        )

        assertEquals(PlaybackPrepareAction.RESOLVE_REQUIRED, outcome.action)
        assertEquals(11L, state.currentQueueId)
        assertEquals(200L, state.currentFileId)
        assertEquals("http://host/api/stream/200", state.currentStreamUrl)
        assertEquals(0L, state.currentStreamOffsetMs)
        assertNull(state.currentRequest)
        assertNull(state.resolutionRequestedFileId)
        assertTrue(!state.currentIsLiveTranscode)
    }
}
