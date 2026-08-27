package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivePlayerLeaseGateTest {
    @Test
    fun onlyActiveUnexpiredAssignmentCanProjectAndReport() {
        var now = 1_000L
        val gate = ActivePlayerLeaseGate { now }

        gate.apply("STANDBY", 0, 45_000)
        assertNull(gate.activeGeneration())

        gate.apply("ACTIVE", 7, 45_000)
        assertEquals(7L, gate.activeGeneration())

        now += 45_000
        assertNull(gate.activeGeneration())
    }

    @Test
    fun disconnectFencesPreviousGeneration() {
        val gate = ActivePlayerLeaseGate { 1_000L }
        gate.apply("ACTIVE", 3, 45_000)

        gate.disconnect()

        assertNull(gate.activeGeneration())
    }
}
