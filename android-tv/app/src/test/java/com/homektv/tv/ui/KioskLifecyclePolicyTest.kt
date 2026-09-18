package com.homektv.tv.ui

import android.view.KeyEvent
import com.homektv.tv.session.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskLifecyclePolicyTest {

    @Test
    fun combinedMode_autoLaunchesKioskOnStartup() {
        val shouldLaunchKiosk = KioskLifecyclePolicy.shouldAutoLaunchKioskOnStart(
            deviceMode = DeviceMode.COMBINED,
            canOpenKiosk = true,
        )
        assertTrue(shouldLaunchKiosk)
    }

    @Test
    fun playerMode_doesNotAutoLaunchKiosk_protectingPureTerminal() {
        val shouldLaunchKiosk = KioskLifecyclePolicy.shouldAutoLaunchKioskOnStart(
            deviceMode = DeviceMode.PLAYER,
            canOpenKiosk = false,
        )
        assertFalse("纯受控播放机模式绝不能自启点歌台，防止崩溃", shouldLaunchKiosk)
    }

    @Test
    fun fullscreenKeyDispatch_centerOrMenuOpensKiosk() {
        assertTrue(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_DPAD_CENTER, isKioskActive = false))
        assertTrue(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_ENTER, isKioskActive = false))
        assertTrue(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_MENU, isKioskActive = false))
        assertFalse(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_DPAD_LEFT, isKioskActive = false))
        assertFalse(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_DPAD_RIGHT, isKioskActive = false))
        assertFalse(KioskLifecyclePolicy.shouldOpenKioskOnKey(KeyEvent.KEYCODE_DPAD_CENTER, isKioskActive = true))
    }

    @Test
    fun backKeyOnDashboard_closesKioskOnlyWhenPlaying() {
        assertEquals(
            KioskBackAction.RETURN_TO_FULLSCREEN_MV,
            KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying = true),
        )
        assertEquals(
            KioskBackAction.STAY_ON_DASHBOARD_OR_PROMPT_EXIT,
            KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying = false),
        )
    }
}
