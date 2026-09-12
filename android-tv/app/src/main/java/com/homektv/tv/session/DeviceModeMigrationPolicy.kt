package com.homektv.tv.session

object DeviceModeMigrationPolicy {
    const val CURRENT_VERSION = 1

    enum class Action {
        NONE,
        PROMPT_COMBINED,
    }

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
