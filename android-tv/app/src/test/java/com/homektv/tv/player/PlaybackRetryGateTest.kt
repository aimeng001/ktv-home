package com.homektv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryGateTest {
    @Test
    fun aRetryIsValidOnlyForTheRequestThatScheduledIt() {
        val gate = PlaybackRetryGate()
        val first = PlaybackRequestIdentity(queueId = 10L, fileId = 20L)
        val second = PlaybackRequestIdentity(queueId = 11L, fileId = 21L)

        val ticket = gate.begin(first)

        assertTrue(gate.isCurrent(ticket, first))
        assertFalse(gate.isCurrent(ticket, second))
    }

    @Test
    fun replacingOrStoppingPlaybackInvalidatesAStaleRetry() {
        val gate = PlaybackRetryGate()
        val first = PlaybackRequestIdentity(queueId = 10L, fileId = 20L)
        val second = PlaybackRequestIdentity(queueId = 11L, fileId = 21L)
        val ticket = gate.begin(first)

        gate.begin(second)
        assertFalse(gate.isCurrent(ticket, first))

        val current = gate.begin(second)
        gate.invalidate()
        assertFalse(gate.isCurrent(current, second))
    }
}
