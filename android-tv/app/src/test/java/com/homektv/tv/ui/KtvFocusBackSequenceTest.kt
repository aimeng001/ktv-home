package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvFocusBackSequenceTest {

    @Test
    fun whenKeyboardHasText_backPressMustClearTextFirst() {
        val focusController = KtvFocusController()
        focusController.isKioskActive = true
        focusController.hasInputText = true

        var textCleared = false
        var exitedKiosk = false

        val action = focusController.handleBackPress(
            onDismissDrawer = {},
            onClearInputText = { textCleared = true },
            onInnerDetailBack = {},
            onExitKiosk = { exitedKiosk = true },
            onExitApp = {},
        )

        assertEquals(BackAction.CLEAR_INPUT_TEXT, action)
        assertTrue(textCleared)
        assertFalse(exitedKiosk)
    }

    @Test
    fun whenKeyboardHasText_shouldInterceptBackReturnsTrue() {
        val focusController = KtvFocusController()
        focusController.isKioskActive = false
        focusController.hasInputText = true

        assertTrue(focusController.shouldInterceptBack(0))
        assertTrue(focusController.shouldInterceptBack(1))
    }

    @Test
    fun drawerTakesPrecedenceOverInputText() {
        val focusController = KtvFocusController()
        focusController.isDrawerOpen = true
        focusController.hasInputText = true

        var drawerDismissed = false
        var textCleared = false

        val action = focusController.handleBackPress(
            onDismissDrawer = { drawerDismissed = true },
            onClearInputText = { textCleared = true },
            onExitKiosk = {},
            onExitApp = {},
        )

        assertEquals(BackAction.DISMISS_DRAWER, action)
        assertTrue(drawerDismissed)
        assertFalse(textCleared)
    }

    @Test
    fun drawerTakesPrecedenceOverInnerDetailBack() {
        val focusController = KtvFocusController()
        focusController.isDrawerOpen = true
        focusController.hasInnerDetailBack = true
        focusController.isKioskActive = true

        var drawerDismissed = false
        var innerBackHandled = false

        val action = focusController.handleBackPress(
            onDismissDrawer = { drawerDismissed = true },
            onClearInputText = {},
            onInnerDetailBack = { innerBackHandled = true },
            onExitKiosk = {},
            onExitApp = {},
        )

        assertEquals(BackAction.DISMISS_DRAWER, action)
        assertTrue(drawerDismissed)
        assertFalse(innerBackHandled)
    }

    @Test
    fun inputTextTakesPrecedenceOverInnerDetailBack() {
        val focusController = KtvFocusController()
        focusController.hasInputText = true
        focusController.hasInnerDetailBack = true
        focusController.isKioskActive = true

        var textCleared = false
        var innerBackHandled = false

        val action = focusController.handleBackPress(
            onDismissDrawer = {},
            onClearInputText = { textCleared = true },
            onInnerDetailBack = { innerBackHandled = true },
            onExitKiosk = {},
            onExitApp = {},
        )

        assertEquals(BackAction.CLEAR_INPUT_TEXT, action)
        assertTrue(textCleared)
        assertFalse(innerBackHandled)
    }

    @Test
    fun innerDetailBackTakesPrecedenceOverExitKiosk() {
        val focusController = KtvFocusController()
        focusController.hasInnerDetailBack = true
        focusController.isKioskActive = true

        var innerBackHandled = false
        var kioskExited = false

        val action = focusController.handleBackPress(
            onDismissDrawer = {},
            onClearInputText = {},
            onInnerDetailBack = { innerBackHandled = true },
            onExitKiosk = { kioskExited = true },
            onExitApp = {},
        )

        assertEquals(BackAction.INNER_DETAIL_BACK, action)
        assertTrue(innerBackHandled)
        assertFalse(kioskExited)
    }

    @Test
    fun exitKioskTakesPrecedenceOverExitApp() {
        val focusController = KtvFocusController()
        focusController.isKioskActive = true

        var kioskExited = false
        var appExited = false

        val action = focusController.handleBackPress(
            onDismissDrawer = {},
            onClearInputText = {},
            onInnerDetailBack = {},
            onExitKiosk = { kioskExited = true },
            onExitApp = { appExited = true },
        )

        assertEquals(BackAction.EXIT_KIOSK, action)
        assertTrue(kioskExited)
        assertFalse(appExited)
    }

    @Test
    fun whenOnlyExitAppRemains() {
        val focusController = KtvFocusController()

        var appExited = false

        val action = focusController.handleBackPress(
            onDismissDrawer = {},
            onClearInputText = {},
            onInnerDetailBack = {},
            onExitKiosk = {},
            onExitApp = { appExited = true },
        )

        assertEquals(BackAction.EXIT_APP, action)
        assertTrue(appExited)
    }

    @Test
    fun shouldInterceptBackHierarchyMatchesHandleBack() {
        val focusController = KtvFocusController()

        // When all false -> does not intercept (falls through to system exit)
        assertFalse(focusController.shouldInterceptBack(0))

        // Any of the 4 conditions causes intercept
        focusController.isDrawerOpen = true
        assertTrue(focusController.shouldInterceptBack(0))
        focusController.isDrawerOpen = false

        focusController.hasInputText = true
        assertTrue(focusController.shouldInterceptBack(0))
        focusController.hasInputText = false

        focusController.hasInnerDetailBack = true
        assertTrue(focusController.shouldInterceptBack(0))
        focusController.hasInnerDetailBack = false

        focusController.isKioskActive = true
        assertTrue(focusController.shouldInterceptBack(0))
        focusController.isKioskActive = false

        assertFalse(focusController.shouldInterceptBack(0))
    }
}
