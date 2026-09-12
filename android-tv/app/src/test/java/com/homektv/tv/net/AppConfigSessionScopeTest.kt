package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppConfigSessionScopeTest {
    @Test
    fun identitiesUseDifferentPreferenceNamespacesPerServer() {
        val first = AppConfig.serverScopedPreferenceKey("user_token_", "nas-a:8080")
        val second = AppConfig.serverScopedPreferenceKey("user_token_", "nas-b:8080")

        assertNotEquals(first, second)
        assertEquals("user_token_nas-a:8080", first)
    }

    @Test
    fun blankServerHasAnExplicitUnconfiguredScope() {
        assertEquals("mode_", AppConfig.serverScopedPreferenceKey("mode_", null))
        assertEquals("mode_", AppConfig.serverScopedPreferenceKey("mode_", "  "))
    }
}
