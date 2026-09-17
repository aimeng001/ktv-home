package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorOutboxTest {
    @Test
    fun failedSendKeepsPlayErrorForReconnect() {
        val outbox = PlaybackErrorOutbox()
        outbox.enqueue(PendingPlaybackError(queueId = 42L, fileId = 7L, message = "cannot read"))

        val sent = outbox.flush(1L) { _, _ -> false }

        assertTrue(sent.isEmpty())
        assertEquals(listOf(42L), outbox.pendingErrors().map(PendingPlaybackError::queueId))
    }

    @Test
    fun reconnectRetriesPendingPlayErrorWithTheNewGeneration() {
        val outbox = PlaybackErrorOutbox()
        outbox.enqueue(PendingPlaybackError(queueId = 42L))
        outbox.flush(1L) { _, _ -> true }

        val generations = mutableListOf<Long>()
        outbox.flush(2L) { _, generation -> generations += generation; true }

        assertEquals(listOf(2L), generations)
        assertEquals(listOf(42L), outbox.pendingErrors().map(PendingPlaybackError::queueId))
    }

    @Test
    fun terminalAckRemovesPlayErrorButNonTerminalAckDoesNot() {
        val outbox = PlaybackErrorOutbox()
        outbox.enqueue(PendingPlaybackError(queueId = 42L))
        outbox.acknowledge(42L, "UNKNOWN")
        assertEquals(listOf(42L), outbox.pendingErrors().map(PendingPlaybackError::queueId))

        outbox.acknowledge(42L, "STALE")
        assertTrue(outbox.pendingErrors().isEmpty())
    }

    @Test
    fun fullQueueRejectsNewPlayErrorAndBoundsMessage() {
        val outbox = PlaybackErrorOutbox(
            (1L..PlaybackErrorOutbox.MAX_PENDING).map {
                PendingPlaybackError(queueId = it, message = "x".repeat(PlaybackErrorOutbox.MAX_MESSAGE_CHARS + 10))
            },
        )

        assertEquals(
            PlaybackErrorOutbox.EnqueueResult.FULL,
            outbox.enqueue(PendingPlaybackError(queueId = PlaybackErrorOutbox.MAX_PENDING + 1L)),
        )
        assertEquals(PlaybackErrorOutbox.MAX_MESSAGE_CHARS, outbox.pendingErrors().first().message.length)
    }
}
