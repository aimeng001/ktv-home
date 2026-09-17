package com.homektv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekGateTest {
    @Test
    fun only_a_newer_server_seek_sequence_is_applied() {
        val gate = PlaybackSeekGate()

        assertTrue(gate.shouldApply(0))
        gate.markApplied(0)
        assertFalse(gate.shouldApply(0))
        assertTrue(gate.shouldApply(1))
        gate.markApplied(1)
        assertFalse(gate.shouldApply(0))
        assertFalse(gate.shouldApply(1))
    }

    @Test
    fun vocal_mode_change_with_same_seek_sequence_does_not_trigger_seek() {
        val gate = PlaybackSeekGate()
        gate.markApplied(3L)

        // On vocal mode toggle, server does not bump seekSequence
        assertFalse(gate.shouldApply(3L))
    }
}
