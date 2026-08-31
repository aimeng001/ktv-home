package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketEpochGateTest {
    @Test
    fun a_late_callback_from_an_older_socket_is_not_current_after_replacement() {
        val gate = SocketEpochGate()

        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
