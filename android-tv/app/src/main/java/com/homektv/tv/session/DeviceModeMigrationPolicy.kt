package com.homektv.tv.session

object DeviceModeMigrationPolicy {
    const val CURRENT_VERSION = 1

    enum class PromptResult {
        SWITCH_TO_COMBINED,
        KEEP_PLAYER,
        DISMISSED,
    }

    enum class Action {
        NONE,
        PROMPT_COMBINED,
    }

    fun shouldRecordMigration(result: PromptResult): Boolean = result != PromptResult.DISMISSED

    fun evaluate(
        savedMode: DeviceMode?,
        recommendedMode: DeviceMode,
        migrationVersion: Int,
    ): Action {
        if (migrationVersion >= CURRENT_VERSION) return Action.NONE
        return if (savedMode == DeviceMode.PLAYER && recommendedMode == DeviceMode.COMBINED) {
            Action.PROMPT_COMBINED
        } else {
            Action.NONE
        }
    }
}
