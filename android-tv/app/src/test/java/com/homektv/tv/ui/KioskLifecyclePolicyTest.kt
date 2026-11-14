package com.homektv.tv.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskLifecyclePolicyTest {

    @Test
    fun tvClientAlwaysAutoLaunchesKioskOnStartup() {
        assertTrue(KioskLifecyclePolicy.shouldAutoLaunchKioskOnStart())
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
    fun backKeyOnDashboard_promptsExitInsteadOfTrappingInFullscreenLoop() {
        assertEquals(
            KioskBackAction.PROMPT_EXIT_WITH_FULLSCREEN,
            KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying = true, exitOnBackEnabled = true),
        )
        assertEquals(
            KioskBackAction.PROMPT_EXIT_ONLY,
            KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying = false, exitOnBackEnabled = true),
        )
        assertEquals(
            KioskBackAction.STAY_ON_DASHBOARD,
            KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying = false, exitOnBackEnabled = false),
        )
    }

    @Test
    fun nonKioskKeyDispatch_neverHijacksDirectionalKeysAndRespectsFocusedButton() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { keyCode ->
            assertEquals(
                NonKioskKeyAction.PASS_TO_FOCUS,
                KioskLifecyclePolicy.resolveNonKioskKeyAction(
                    keyCode = keyCode,
                    focusedViewIsInteractive = false,
                ),
            )
            assertEquals(
                NonKioskKeyAction.PASS_TO_FOCUS,
                KioskLifecyclePolicy.resolveNonKioskKeyAction(
                    keyCode = keyCode,
                    focusedViewIsInteractive = true,
                ),
            )
        }

        assertEquals(
            NonKioskKeyAction.PASS_TO_FOCUS,
            KioskLifecyclePolicy.resolveNonKioskKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                focusedViewIsInteractive = true,
            ),
        )
        assertEquals(
            NonKioskKeyAction.OPEN_KIOSK,
            KioskLifecyclePolicy.resolveNonKioskKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                focusedViewIsInteractive = false,
            ),
        )
        assertEquals(
            NonKioskKeyAction.OPEN_KIOSK,
            KioskLifecyclePolicy.resolveNonKioskKeyAction(
                keyCode = KeyEvent.KEYCODE_ENTER,
                focusedViewIsInteractive = false,
            ),
        )
        assertEquals(
            NonKioskKeyAction.TOGGLE_PLAYBACK,
            KioskLifecyclePolicy.resolveNonKioskKeyAction(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                focusedViewIsInteractive = false,
            ),
        )
        assertEquals(
            NonKioskKeyAction.NEXT_SONG,
            KioskLifecyclePolicy.resolveNonKioskKeyAction(
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                focusedViewIsInteractive = false,
            ),
        )
    }
}
