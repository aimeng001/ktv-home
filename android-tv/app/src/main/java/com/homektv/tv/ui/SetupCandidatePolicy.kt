package com.homektv.tv.ui

import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.ServerSessionScope

internal object SetupCandidatePolicy {
    fun resolve(
        hostPort: String,
        candidate: DiscoveredServer?,
        knownServer: SavedServer? = null,
    ): SavedServer {
        val normalized = AppConfig.normalizeHost(hostPort) ?: hostPort.trim()
        return if (candidate != null && candidate.hostPort == normalized) {
            SavedServer(candidate.hostPort, candidate.name, candidate.instanceId)
        } else if (knownServer != null && knownServer.hostPort == normalized) {
            knownServer
        } else {
            SavedServer(normalized, normalized)
        }
    }

    fun shouldReuseInitialCredential(
        initialServer: SavedServer?,
        targetServer: SavedServer,
        credentialChanged: Boolean,
        legacyMigrationAccepted: Boolean = false,
    ): Boolean {
        if (credentialChanged) return true
        val initialIdentity = ServerSessionScope.normalizeInstanceId(initialServer?.instanceId)
        val targetIdentity = ServerSessionScope.normalizeInstanceId(targetServer.instanceId)
        return when {
            // A host-only credential is not proof that a newly identified
            // instance is the same server. It may cross the boundary only
            // after the explicit migration confirmation.
            initialIdentity == null && targetIdentity != null && initialServer?.hostPort == targetServer.hostPort ->
                legacyMigrationAccepted
            initialIdentity != null || targetIdentity != null ->
                initialIdentity != null && initialIdentity == targetIdentity
            else -> initialServer?.hostPort == targetServer.hostPort
        }
    }
}
