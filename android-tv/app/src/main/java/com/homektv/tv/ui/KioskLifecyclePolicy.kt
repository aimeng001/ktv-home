package com.homektv.tv.ui

import android.view.KeyEvent
import com.homektv.tv.session.DeviceMode

enum class KioskBackAction {
    RETURN_TO_FULLSCREEN_MV,
    STAY_ON_DASHBOARD_OR_PROMPT_EXIT,
}

object KioskLifecyclePolicy {
    fun shouldAutoLaunchKioskOnStart(deviceMode: DeviceMode, canOpenKiosk: Boolean): Boolean =
        deviceMode == DeviceMode.COMBINED && canOpenKiosk

    fun shouldOpenKioskOnKey(keyCode: Int, isKioskActive: Boolean): Boolean {
        if (isKioskActive) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }

    fun resolveDashboardBackAction(hasPlaying: Boolean): KioskBackAction =
        if (hasPlaying) {
            KioskBackAction.RETURN_TO_FULLSCREEN_MV
        } else {
            KioskBackAction.STAY_ON_DASHBOARD_OR_PROMPT_EXIT
        }
}
