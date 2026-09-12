package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvFocusTopologyTest {

    @Test
    fun shouldInterceptBack_whenDrawerOpen_interceptsBothDownAndUp() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = true
        controller.isKioskActive = true

        // ACTION_DOWN (0) and ACTION_UP (1)
        assertTrue(controller.shouldInterceptBack(0))
        assertTrue(controller.shouldInterceptBack(1))
    }

    @Test
    fun shouldInterceptBack_whenKioskActiveOnly_interceptsBothDownAndUp() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = false
        controller.isKioskActive = true

        assertTrue(controller.shouldInterceptBack(0))
        assertTrue(controller.shouldInterceptBack(1))
    }

    @Test
    fun shouldInterceptBack_whenNeitherActive_doesNotIntercept() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = false
        controller.isKioskActive = false

        assertFalse(controller.shouldInterceptBack(0))
        assertFalse(controller.shouldInterceptBack(1))
    }

    @Test
    fun handleBackPress_prioritizesDrawerOverKiosk() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = true
        controller.isKioskActive = true

        var drawerDismissed = false
        var kioskExited = false
        var appExited = false

        val action = controller.handleBackPress(
            onDismissDrawer = { drawerDismissed = true },
            onExitKiosk = { kioskExited = true },
            onExitApp = { appExited = true },
        )

        assertEquals(BackAction.DISMISS_DRAWER, action)
        assertTrue(drawerDismissed)
        assertFalse(kioskExited)
        assertFalse(appExited)
    }

    @Test
    fun handleBackPress_whenKioskOnly_exitsKiosk() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = false
        controller.isKioskActive = true

        var drawerDismissed = false
        var kioskExited = false
        var appExited = false

        val action = controller.handleBackPress(
            onDismissDrawer = { drawerDismissed = true },
            onExitKiosk = { kioskExited = true },
            onExitApp = { appExited = true },
        )

        assertEquals(BackAction.EXIT_KIOSK, action)
        assertFalse(drawerDismissed)
        assertTrue(kioskExited)
        assertFalse(appExited)
    }

    @Test
    fun handleBackPress_whenInArtistDetail_returnsToSingersGrid() {
        val controller = KtvFocusController()
        controller.isKioskActive = true
        controller.hasInnerDetailBack = true

        var detailBackCalled = false
        var kioskExited = false

        val action = controller.handleBackPress(
            onDismissDrawer = {},
            onInnerDetailBack = { detailBackCalled = true },
            onExitKiosk = { kioskExited = true },
            onExitApp = {},
        )

        assertEquals(BackAction.INNER_DETAIL_BACK, action)
        assertTrue(detailBackCalled)
        assertFalse(kioskExited)
    }

    @Test
    fun shouldInterceptMenu_whenKioskActive_returnsTrue() {
        val controller = KtvFocusController()
        controller.isKioskActive = true
        assertTrue(controller.shouldInterceptMenu())
    }

    @Test
    fun shouldInterceptMenu_whenDrawerOpen_returnsTrue() {
        val controller = KtvFocusController()
        controller.isDrawerOpen = true
        assertTrue(controller.shouldInterceptMenu())
    }

    @Test
    fun shouldInterceptMenu_whenNeitherActive_returnsFalse() {
        val controller = KtvFocusController()
        controller.isKioskActive = false
        controller.isDrawerOpen = false
        assertFalse(controller.shouldInterceptMenu())
    }
}
