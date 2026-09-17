package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSessionScopeTest {
    private val legacy = SavedServer("nas-a:8080", "客厅")
    private val sameInstance = legacy.copy(instanceId = "550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun knownInstanceUsesIdentityNamespaceInsteadOfHostNamespace() {
        assertEquals(
            "player_token_instance_550e8400-e29b-41d4-a716-446655440000",
            ServerSessionScope.preferenceKey("player_token_", sameInstance),
        )
        assertEquals(
            "player_token_nas-a:8080",
            ServerSessionScope.hostPreferenceKey("player_token_", sameInstance.hostPort),
        )
    }

    @Test
    fun legacyServerKeepsHostNamespace() {
        assertEquals(
            "user_token_nas-a:8080",
            ServerSessionScope.preferenceKey("user_token_", legacy),
        )
    }
}
