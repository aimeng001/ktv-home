package com.homektv.tv.ui

import com.homektv.tv.net.SavedServer
import com.homektv.tv.session.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupCommitPolicyTest {
    private val identityA = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun untouchedLegacyValuesRequireExplicitMigrationChoice() {
        val legacy = SavedServer("nas-a:8080", "旧服务")
        val identified = legacy.copy(instanceId = identityA)

        assertEquals("", SetupCommitPolicy.nickname(legacy, identified, "旧昵称", "旧昵称"))
        assertEquals(
            DeviceMode.COMBINED,
            SetupCommitPolicy.mode(legacy, identified, DeviceMode.PLAYER, DeviceMode.PLAYER, DeviceMode.COMBINED),
        )
        assertEquals(
            "旧昵称",
            SetupCommitPolicy.nickname(legacy, identified, "旧昵称", "旧昵称", legacyMigrationAccepted = true),
        )
        assertEquals(
            DeviceMode.PLAYER,
            SetupCommitPolicy.mode(
                legacy, identified, DeviceMode.PLAYER, DeviceMode.PLAYER, DeviceMode.COMBINED,
                legacyMigrationAccepted = true,
            ),
        )
    }

    @Test
    fun untouchedLegacyValuesAreNotCopiedToDifferentHost() {
        val legacy = SavedServer("nas-a:8080", "旧服务")
        val differentHost = SavedServer("nas-b:8080", "旧服务", identityA)

        assertEquals("", SetupCommitPolicy.nickname(legacy, differentHost, "旧昵称", "旧昵称"))
        assertEquals(
            DeviceMode.COMBINED,
            SetupCommitPolicy.mode(legacy, differentHost, DeviceMode.PLAYER, DeviceMode.PLAYER, DeviceMode.COMBINED),
        )
    }

    @Test
    fun explicitChangesAreKeptForNewIdentity() {
        val legacy = SavedServer("nas-a:8080", "旧服务")
        val identified = legacy.copy(instanceId = identityA)

        assertEquals("新昵称", SetupCommitPolicy.nickname(legacy, identified, "旧昵称", "新昵称"))
        assertEquals(
            DeviceMode.CONTROLLER,
            SetupCommitPolicy.mode(
                legacy,
                identified,
                DeviceMode.PLAYER,
                DeviceMode.CONTROLLER,
                DeviceMode.COMBINED,
            ),
        )
    }

    @Test
    fun unchangedValuesRemainForTheSameIdentity() {
        val initial = SavedServer("nas-a:8080", "服务", identityA)

        assertEquals("旧昵称", SetupCommitPolicy.nickname(initial, initial, "旧昵称", "旧昵称"))
        assertEquals(
            DeviceMode.PLAYER,
            SetupCommitPolicy.mode(initial, initial, DeviceMode.PLAYER, DeviceMode.PLAYER, DeviceMode.COMBINED),
        )
    }
}
