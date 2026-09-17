package com.homektv.tv.ui

import com.homektv.tv.net.SavedServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyIdentityMigrationPolicyTest {
    private val identity = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun sameHostGainingAnInstanceIdRequiresExplicitChoice() {
        assertTrue(
            LegacyIdentityMigrationPolicy.requiresConfirmation(
                SavedServer("nas-a:8080", "旧服务"),
                SavedServer("nas-a:8080", "新服务", identity),
            ),
        )
    }

    @Test
    fun differentHostOrAlreadyIdentifiedServerDoesNotTriggerMigration() {
        assertFalse(
            LegacyIdentityMigrationPolicy.requiresConfirmation(
                SavedServer("nas-a:8080", "旧服务"),
                SavedServer("nas-b:8080", "新服务", identity),
            ),
        )
        assertFalse(
            LegacyIdentityMigrationPolicy.requiresConfirmation(
                SavedServer("nas-a:8080", "旧服务", identity),
                SavedServer("nas-a:8080", "新服务", identity),
            ),
        )
    }
}
