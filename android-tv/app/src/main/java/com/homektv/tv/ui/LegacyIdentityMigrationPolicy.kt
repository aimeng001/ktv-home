package com.homektv.tv.ui

import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.ServerSessionScope

/** Explicitly gates the only ambiguous identity transition: host-only -> instance scoped. */
internal object LegacyIdentityMigrationPolicy {
    fun requiresConfirmation(initial: SavedServer?, target: SavedServer): Boolean {
        val initialIdentity = ServerSessionScope.normalizeInstanceId(initial?.instanceId)
        val targetIdentity = ServerSessionScope.normalizeInstanceId(target.instanceId)
        return initial != null && initial.hostPort == target.hostPort &&
            initialIdentity == null && targetIdentity != null
    }
}
