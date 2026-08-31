package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectGateTest {
    @Test
    fun repeatedDisconnectCallbacksScheduleOnlyOneReconnectUntilItRuns() {
        val gate = ReconnectGate()

        assertTrue(gate.trySchedule())
        assertFalse(gate.trySchedule())

        gate.markRun()

        assertTrue(gate.trySchedule())
    }
}
