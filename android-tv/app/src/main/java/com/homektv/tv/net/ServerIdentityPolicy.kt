package com.homektv.tv.net

/**
 * Determines whether a discovered endpoint can be treated as the same
 * logical server. An absent identity is intentionally never auto-trusted.
 */
object ServerIdentityPolicy {
    fun isSameServer(current: SavedServer, candidate: DiscoveredServer): Boolean =
        ServerSessionScope.normalizeInstanceId(current.instanceId) != null &&
            ServerSessionScope.normalizeInstanceId(current.instanceId) ==
            ServerSessionScope.normalizeInstanceId(candidate.instanceId)
}
