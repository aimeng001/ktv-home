package com.homektv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLoadGateTest {
    @Test
    fun onlyTheLatestLoadTicketRemainsCurrent() {
        val gate = PlaybackLoadGate()

        val first = gate.begin(10L)
        val second = gate.begin(11L)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun idleInvalidatesAStillRunningLoad() {
        val gate = PlaybackLoadGate()
        val ticket = gate.begin(10L)

        gate.invalidate()

        assertFalse(gate.isCurrent(ticket))
    }

    @Test
    fun nullableLegacyQueueIdStillUsesGenerationForIdentity() {
        val gate = PlaybackLoadGate()
        val first = gate.begin(null)
        val second = gate.begin(null)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
