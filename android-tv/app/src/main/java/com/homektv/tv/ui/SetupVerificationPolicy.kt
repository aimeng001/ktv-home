package com.homektv.tv.ui

import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.ServerSessionScope

/**
 * Converts a completed health/readiness probe into the only server record that
 * setup is allowed to persist. The response, not the user-entered candidate,
 * is authoritative for the stable instance identity.
 */
internal object SetupVerificationPolicy {
    fun confirm(requested: SavedServer, discovered: DiscoveredServer): SavedServer? {
        if (requested.hostPort != discovered.hostPort) return null

        val expectedRaw = requested.instanceId?.trim().orEmpty()
        val expected = ServerSessionScope.normalizeInstanceId(requested.instanceId)
        if (expectedRaw.isNotEmpty() && expected == null) return null

        val actualRaw = discovered.instanceId?.trim().orEmpty()
        val actual = ServerSessionScope.normalizeInstanceId(discovered.instanceId)
        if (actualRaw.isNotEmpty() && actual == null) return null
        if (expected != null && expected != actual) return null

        val name = discovered.name.trim().ifEmpty { requested.name.trim() }
            .ifEmpty { requested.hostPort }
        return SavedServer(discovered.hostPort, name, actual)
    }
}
