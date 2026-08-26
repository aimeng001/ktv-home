package com.homektv.tv.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestIdentityTest {
    @Test
    fun sameQueueAndFileCanReuseAnActivePlayback() {
        val request = PlaybackRequestIdentity(queueId = 10L, fileId = 20L)

        assertTrue(shouldReusePlaybackRequest(request, request, Player.STATE_READY))
    }

    @Test
    fun sameFileInDifferentQueueMustReload() {
        assertFalse(
            shouldReusePlaybackRequest(
                current = PlaybackRequestIdentity(queueId = 10L, fileId = 20L),
                requested = PlaybackRequestIdentity(queueId = 11L, fileId = 20L),
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun idlePlaybackMustNotBeReused() {
        val request = PlaybackRequestIdentity(queueId = 10L, fileId = 20L)

        assertFalse(shouldReusePlaybackRequest(request, request, Player.STATE_IDLE))
    }

    @Test
    fun nullQueueIdsRemainCompatibleForLegacyRequests() {
        val request = PlaybackRequestIdentity(queueId = null, fileId = 20L)

        assertTrue(shouldReusePlaybackRequest(request, request, Player.STATE_BUFFERING))
    }
}
