package com.homektv.tv.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModeMigrationPolicyTest {

    @Test
    fun dismissingMigrationPromptDoesNotRecordCompletion() {
        assertEquals(
            false,
            DeviceModeMigrationPolicy.shouldRecordMigration(DeviceModeMigrationPolicy.PromptResult.DISMISSED),
        )
    }

    @Test
    fun explicitMigrationChoicesRecordCompletion() {
        assertEquals(
            true,
            DeviceModeMigrationPolicy.shouldRecordMigration(DeviceModeMigrationPolicy.PromptResult.KEEP_PLAYER),
        )
        assertEquals(
            true,
            DeviceModeMigrationPolicy.shouldRecordMigration(DeviceModeMigrationPolicy.PromptResult.SWITCH_TO_COMBINED),
        )
    }

    @Test
    fun legacyPlayerOnTvRequiresMigrationPrompt() {
        val action = DeviceModeMigrationPolicy.evaluate(
            savedMode = DeviceMode.PLAYER,
            recommendedMode = DeviceMode.COMBINED,
            migrationVersion = 0,
        )
        assertEquals(DeviceModeMigrationPolicy.Action.PROMPT_COMBINED, action)
    }

    @Test
    fun alreadyMigratedDeviceNeverPromptsAgain() {
        val action = DeviceModeMigrationPolicy.evaluate(
            savedMode = DeviceMode.PLAYER,
            recommendedMode = DeviceMode.COMBINED,
            migrationVersion = 1,
        )
        assertEquals(DeviceModeMigrationPolicy.Action.NONE, action)
    }

    @Test
    fun unconfiguredDeviceDoesNotPrompt() {
        val action = DeviceModeMigrationPolicy.evaluate(
            savedMode = null,
            recommendedMode = DeviceMode.COMBINED,
            migrationVersion = 0,
        )
        assertEquals(DeviceModeMigrationPolicy.Action.NONE, action)
    }

    @Test
    fun nonTelevisionDoesNotPrompt() {
        val action = DeviceModeMigrationPolicy.evaluate(
            savedMode = DeviceMode.PLAYER,
            recommendedMode = DeviceMode.CONTROLLER,
            migrationVersion = 0,
        )
        assertEquals(DeviceModeMigrationPolicy.Action.NONE, action)
    }
}
