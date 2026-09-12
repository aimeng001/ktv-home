package com.homektv.tv.ui

import com.homektv.tv.session.DeviceCapabilities
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceModeRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModePreferenceTest {

    @Test
    fun manualOverride_persistsOverAutoRecommendation() {
        // TV capability recommends COMBINED by default (P0-01)
        val tvCap = DeviceCapabilities(isTelevision = true, hasTouchscreen = false)
        val recommended = DeviceModeRouter.recommend(tvCap)
        assertEquals(DeviceMode.COMBINED, recommended)

        // When user explicitly selects PLAYER mode, it must resolve to PLAYER
        val resolved = DeviceModeRouter.resolve(recommended, DeviceMode.PLAYER)
        assertEquals(DeviceMode.PLAYER, resolved)
    }

    @Test
    fun manualOverride_controllerMode_resolvesCorrectly() {
        val tvCap = DeviceCapabilities(isTelevision = true, hasTouchscreen = false)
        val recommended = DeviceModeRouter.recommend(tvCap)
        val resolved = DeviceModeRouter.resolve(recommended, DeviceMode.CONTROLLER)
        assertEquals(DeviceMode.CONTROLLER, resolved)
    }

    @Test
    fun backKeyNavigation_routesByPriority() {
        val focusController = KtvFocusController()

        // Case 1: Drawer is open -> Back dismisses drawer
        focusController.isDrawerOpen = true
        focusController.isKioskActive = true
        val handled1 = focusController.handleBackPress(
            onDismissDrawer = { focusController.isDrawerOpen = false },
            onExitKiosk = { focusController.isKioskActive = false },
            onExitApp = {},
        )
        assertEquals(BackAction.DISMISS_DRAWER, handled1)
        assertEquals(false, focusController.isDrawerOpen)
        assertEquals(true, focusController.isKioskActive)

        // Case 2: Kiosk is active -> Back exits kiosk back to fullscreen MV
        val handled2 = focusController.handleBackPress(
            onDismissDrawer = {},
            onExitKiosk = { focusController.isKioskActive = false },
            onExitApp = {},
        )
        assertEquals(BackAction.EXIT_KIOSK, handled2)
        assertEquals(false, focusController.isKioskActive)

        // Case 3: Already in fullscreen -> Back triggers app exit
        var exitCalled = false
        val handled3 = focusController.handleBackPress(
            onDismissDrawer = {},
            onExitKiosk = {},
            onExitApp = { exitCalled = true },
        )
        assertEquals(BackAction.EXIT_APP, handled3)
        assertEquals(true, exitCalled)
    }
}
