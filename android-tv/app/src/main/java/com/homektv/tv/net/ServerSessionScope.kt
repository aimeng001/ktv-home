package com.homektv.tv.net

import java.util.UUID

/**
 * Namespaces persisted session state by server instance when the server has a
 * stable identity. Host-only keys remain the compatibility namespace for old
 * servers and old clients.
 */
internal object ServerSessionScope {
    fun preferenceKey(prefix: String, server: SavedServer): String =
        server.instanceId?.let(::normalizeInstanceId)?.let { identityPreferenceKey(prefix, it) }
            ?: hostPreferenceKey(prefix, server.hostPort)

    fun hostPreferenceKey(prefix: String, hostPort: String?): String =
        prefix + hostPort.orEmpty().trim()

    fun identityPreferenceKey(prefix: String, instanceId: String): String =
        prefix + "instance_" + instanceId

    fun normalizeInstanceId(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uuid = UUID.fromString(trimmed)
            uuid.toString().takeIf { it.equals(trimmed, ignoreCase = true) }?.lowercase()
        }.getOrNull()
    }

}
