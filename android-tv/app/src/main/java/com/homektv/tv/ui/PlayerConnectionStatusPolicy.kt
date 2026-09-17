package com.homektv.tv.ui

internal enum class PlayerConnectionStatus {
    CONNECTING,
    ONLINE,
    STANDBY,
}

/** Keeps transport state and standby-player role visible as separate UI states. */
internal object PlayerConnectionStatusPolicy {
    fun onConnectionChanged(connected: Boolean, playerActive: Boolean): PlayerConnectionStatus =
        status(connected, playerActive)

    fun onPlayerRole(active: Boolean, connected: Boolean): PlayerConnectionStatus =
        status(connected, active)

    private fun status(connected: Boolean, playerActive: Boolean): PlayerConnectionStatus = when {
        !connected -> PlayerConnectionStatus.CONNECTING
        !playerActive -> PlayerConnectionStatus.STANDBY
        else -> PlayerConnectionStatus.ONLINE
    }
}
