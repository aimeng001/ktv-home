package com.homektv.tv.net

/** Identity filter used by recovery discovery; it never treats host/name as identity. */
internal object DiscoveryIdentityPolicy {

    fun firstMatching(
        expectedInstanceId: String?,
        servers: List<DiscoveredServer>,
    ): List<DiscoveredServer> {
        val expected = ServerSessionScope.normalizeInstanceId(expectedInstanceId) ?: return emptyList()
        return servers.filter {
            ServerSessionScope.normalizeInstanceId(it.instanceId) == expected
        }.map { candidate ->
            candidate.copy(instanceId = expected)
        }
    }

    fun firstMatchingStage(
        expectedInstanceId: String?,
        stages: Iterable<List<DiscoveredServer>>,
    ): List<DiscoveredServer> {
        val expected = ServerSessionScope.normalizeInstanceId(expectedInstanceId) ?: return emptyList()
        for (stage in stages) {
            val matches = firstMatching(expected, stage)
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }
}
