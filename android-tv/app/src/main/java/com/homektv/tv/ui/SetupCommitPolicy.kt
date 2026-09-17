package com.homektv.tv.ui

import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.ServerSessionScope
import com.homektv.tv.session.DeviceMode

/** Prevents setup from silently carrying identity-scoped values to a new server. */
internal object SetupCommitPolicy {
    fun nickname(
        initialServer: SavedServer?,
        targetServer: SavedServer,
        initialValue: String,
        enteredValue: String,
        legacyMigrationAccepted: Boolean = false,
    ): String = if (sameScope(initialServer, targetServer, legacyMigrationAccepted) || enteredValue != initialValue) {
        enteredValue
    } else {
        ""
    }

    fun mode(
        initialServer: SavedServer?,
        targetServer: SavedServer,
        initialValue: DeviceMode,
        selectedValue: DeviceMode,
        recommendedValue: DeviceMode,
        legacyMigrationAccepted: Boolean = false,
    ): DeviceMode = if (sameScope(initialServer, targetServer, legacyMigrationAccepted) || selectedValue != initialValue) {
        selectedValue
    } else {
        recommendedValue
    }

    private fun sameScope(
        initialServer: SavedServer?,
        targetServer: SavedServer,
        legacyMigrationAccepted: Boolean,
    ): Boolean {
        val initialIdentity = ServerSessionScope.normalizeInstanceId(initialServer?.instanceId)
        val targetIdentity = ServerSessionScope.normalizeInstanceId(targetServer.instanceId)
        return if (initialIdentity == null && targetIdentity != null && initialServer?.hostPort == targetServer.hostPort) {
            legacyMigrationAccepted
        } else if (initialIdentity != null || targetIdentity != null) {
            initialIdentity != null && initialIdentity == targetIdentity
        } else {
            initialServer?.hostPort == targetServer.hostPort
        }
    }
}
