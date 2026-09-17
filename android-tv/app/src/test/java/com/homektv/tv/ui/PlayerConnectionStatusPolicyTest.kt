package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerConnectionStatusPolicyTest {
    @Test
    fun standbyRoleIsRenderedAsConnectedStandby() {
        assertEquals(
            PlayerConnectionStatus.STANDBY,
            PlayerConnectionStatusPolicy.onPlayerRole(active = false, connected = true),
        )
    }

    @Test
    fun transportDisconnectStillTakesStandbyBackToConnecting() {
        assertEquals(
            PlayerConnectionStatus.CONNECTING,
            PlayerConnectionStatusPolicy.onConnectionChanged(connected = false, playerActive = true),
        )
    }

    @Test
    fun connectionSuccessDoesNotHideStandbyRole() {
        assertEquals(
            PlayerConnectionStatus.STANDBY,
            PlayerConnectionStatusPolicy.onConnectionChanged(connected = true, playerActive = false),
        )
    }

    @Test
    fun activeLeaseBeforeConnectionStillShowsConnecting() {
        assertEquals(
            PlayerConnectionStatus.CONNECTING,
            PlayerConnectionStatusPolicy.onPlayerRole(active = true, connected = false),
        )
    }
}
