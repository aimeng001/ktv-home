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
}
