package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReportGateTest {
    @Test
    fun reportsFirstPositionAndThenUsesElapsedTimeInsteadOfMediaPosition() {
        val gate = ProgressReportGate(intervalMs = 1_000L)

        assertTrue(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 5_000L))
        gate.markReported(queueId = 10L, elapsedRealtimeMs = 5_000L)

        assertFalse(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 5_999L))
        assertTrue(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 6_000L))
    }

    @Test
    fun queueChangeReportsImmediatelyEvenWhenNewSongStartsAtLowerPosition() {
        val gate = ProgressReportGate(intervalMs = 1_000L)
        gate.markReported(queueId = 10L, elapsedRealtimeMs = 5_000L)

        assertTrue(gate.shouldReport(queueId = 11L, elapsedRealtimeMs = 5_100L))
    }

    @Test
    fun backwardSeekAndReplayDoNotDelayTheNextTimeBasedReport() {
        val gate = ProgressReportGate(intervalMs = 1_000L)
        gate.markReported(queueId = 10L, elapsedRealtimeMs = 5_000L)

        assertFalse(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 5_500L))
        assertTrue(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 6_000L))
        gate.markReported(queueId = 10L, elapsedRealtimeMs = 6_000L)
        assertFalse(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 6_999L))
        assertTrue(gate.shouldReport(queueId = 10L, elapsedRealtimeMs = 7_000L))
    }
}
