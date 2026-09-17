package com.homektv.tv.session

import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.ServerIdentityPolicy

/**
 * Keeps discovery separate from an active server-bound session.
 * A discovered endpoint is only reported to the session owner; this class
 * never persists or mutates the active endpoint.
 */
class ServerRecoveryCoordinator(
    private val currentServer: SavedServer?,
    private val onCandidate: (DiscoveredServer) -> Unit,
) {
    @Synchronized
    fun reportDiscovered(candidate: DiscoveredServer): RecoveryAction {
        return reportDiscovered(listOf(candidate))
    }

    @Synchronized
    fun reportDiscovered(candidates: Iterable<DiscoveredServer>): RecoveryAction {
        val current = currentServer ?: return RecoveryAction.IGNORE
        val candidate = candidates.firstOrNull {
            it.hostPort != current.hostPort && ServerIdentityPolicy.isSameServer(current, it)
        }
            ?: return RecoveryAction.IGNORE
        onCandidate(candidate)
        return RecoveryAction.PROMPT_RESTART
    }
}

enum class RecoveryAction {
    IGNORE,
    PROMPT_RESTART,
}
