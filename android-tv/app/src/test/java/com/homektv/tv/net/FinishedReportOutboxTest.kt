package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedReportOutboxTest {
    @Test
    fun failedSendKeepsFinishedReportForReconnect() {
        val outbox = FinishedReportOutbox()
        outbox.enqueue(42L)

        val sent = outbox.flush(1L) { _, _ -> false }

        assertTrue(sent.isEmpty())
        assertEquals(listOf(42L), outbox.pendingQueueIds())
    }

    @Test
    fun reconnectRetriesPendingReportWithTheNewGeneration() {
        val outbox = FinishedReportOutbox()
        outbox.enqueue(42L)
        outbox.flush(1L) { _, _ -> true }
        outbox.onDisconnected()

        val generations = mutableListOf<Long>()
        outbox.flush(2L) { _, generation -> generations += generation; true }

        assertEquals(listOf(2L), generations)
        assertEquals(listOf(42L), outbox.pendingQueueIds())
    }

    @Test
    fun terminalAckRemovesReportButTransportFailureDoesNot() {
        val outbox = FinishedReportOutbox()
        outbox.enqueue(42L)
        outbox.acknowledge(42L, "UNKNOWN")
        assertEquals(listOf(42L), outbox.pendingQueueIds())

        outbox.acknowledge(42L, "ALREADY_APPLIED")
        assertTrue(outbox.pendingQueueIds().isEmpty())
    }
}
