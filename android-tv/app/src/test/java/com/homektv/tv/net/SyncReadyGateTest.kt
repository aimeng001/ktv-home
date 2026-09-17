package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReadyGateTest {
    @Test
    fun connectionBecomesReadyOnlyAfterCurrentSocketDeliversSync() {
        val gate = SyncReadyGate()

        assertFalse(gate.markReady())
        gate.onOpen()
        assertFalse(gate.isReady())
        assertTrue(gate.markReady())
        assertTrue(gate.isReady())

        gate.onDisconnect()
        assertFalse(gate.isReady())
    }

    @Test
    fun playerRoleEventEstablishesConnectionWithoutPretendingSyncIsReady() {
        assertTrue(ConnectionEventPolicy.establishesConnection("player_role"))
        assertFalse(ConnectionEventPolicy.establishesConnection("toast"))
    }
}
