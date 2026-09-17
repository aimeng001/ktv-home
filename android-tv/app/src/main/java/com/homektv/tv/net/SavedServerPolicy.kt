package com.homektv.tv.net

/** Pure, bounded merge policy for the remembered-server list. */
internal object SavedServerPolicy {
    fun merge(existing: List<SavedServer>, incoming: SavedServer, maxEntries: Int = 10): List<SavedServer> {
        require(maxEntries > 0)
        val normalizedIncoming = incoming.copy(
            hostPort = incoming.hostPort.trim(),
            name = incoming.name.trim().ifEmpty { incoming.hostPort.trim() },
            instanceId = ServerSessionScope.normalizeInstanceId(incoming.instanceId),
        )
        return buildList {
            add(normalizedIncoming)
            existing.forEach { saved ->
                val sameHost = saved.hostPort == normalizedIncoming.hostPort
                val savedId = ServerSessionScope.normalizeInstanceId(saved.instanceId)
                val sameIdentity = normalizedIncoming.instanceId != null && savedId == normalizedIncoming.instanceId
                if (!sameHost && !sameIdentity) {
                    add(saved.copy(instanceId = savedId))
                }
            }
        }.take(maxEntries)
    }
}
