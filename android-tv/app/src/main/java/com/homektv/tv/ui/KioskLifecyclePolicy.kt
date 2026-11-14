package com.homektv.tv.ui

import android.view.KeyEvent

enum class KioskBackAction {
    PROMPT_EXIT_WITH_FULLSCREEN,
    PROMPT_EXIT_ONLY,
    RETURN_TO_FULLSCREEN_MV,
    STAY_ON_DASHBOARD,
}

enum class NonKioskKeyAction {
    PASS_TO_FOCUS,
    OPEN_KIOSK,
    TOGGLE_PLAYBACK,
    NEXT_SONG,
}

object KioskLifecyclePolicy {
    fun shouldAutoLaunchKioskOnStart(): Boolean = true

    fun shouldOpenKioskOnKey(keyCode: Int, isKioskActive: Boolean): Boolean {
        if (isKioskActive) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }

    fun resolveNonKioskKeyAction(
        keyCode: Int,
        focusedViewIsInteractive: Boolean,
    ): NonKioskKeyAction = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER -> when {
            focusedViewIsInteractive -> NonKioskKeyAction.PASS_TO_FOCUS
            else -> NonKioskKeyAction.OPEN_KIOSK
        }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> NonKioskKeyAction.TOGGLE_PLAYBACK
        KeyEvent.KEYCODE_MEDIA_NEXT -> NonKioskKeyAction.NEXT_SONG
        else -> NonKioskKeyAction.PASS_TO_FOCUS
    }

    fun resolveDashboardBackAction(
        hasPlaying: Boolean,
        exitOnBackEnabled: Boolean = true,
    ): KioskBackAction = when {
        exitOnBackEnabled && hasPlaying -> KioskBackAction.PROMPT_EXIT_WITH_FULLSCREEN
        exitOnBackEnabled -> KioskBackAction.PROMPT_EXIT_ONLY
        hasPlaying -> KioskBackAction.RETURN_TO_FULLSCREEN_MV
        else -> KioskBackAction.STAY_ON_DASHBOARD
    }
}
