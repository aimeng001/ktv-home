package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvBrowserFocusRestorationPolicyTest {
    @Test
    fun restoresSelectedRouteCapsuleWhenContentRenderDropsBrowserFocus() {
        assertTrue(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = false,
                browserHadFocusBeforeRender = true,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
            ),
        )
    }

    @Test
    fun explicitRouteActivationRestoresCapsuleEvenIfBrowserWasAlreadyUnfocused() {
        assertTrue(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = true,
                browserHadFocusBeforeRender = false,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
            ),
        )
    }

    @Test
    fun explicitRouteActivationReanchorsFromAnInternalResultsControl() {
        assertTrue(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = true,
                browserHadFocusBeforeRender = true,
                browserHasFocus = true,
                anotherVisibleActionHasFocus = false,
            ),
        )
    }

    @Test
    fun restoresSelectedRouteWhenNoVisibleViewOwnsFocusAfterAnAsyncContentRefresh() {
        assertTrue(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = false,
                browserHadFocusBeforeRender = false,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
                noVisibleViewHasFocus = true,
            ),
        )
    }

    @Test
    fun preservesExistingFocusAndRejectsStaleOrHiddenRoutes() {
        assertFalse(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = false,
                browserHadFocusBeforeRender = true,
                browserHasFocus = true,
                anotherVisibleActionHasFocus = false,
            ),
        )
        assertFalse(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = false,
                routeActivationPending = true,
                browserHadFocusBeforeRender = true,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
            ),
        )
        assertFalse(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = false,
                selectedRouteStillCurrent = true,
                routeActivationPending = true,
                browserHadFocusBeforeRender = true,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
            ),
        )
        assertFalse(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = false,
                browserHadFocusBeforeRender = false,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = false,
            ),
        )
        assertFalse(
            KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                browserVisible = true,
                selectedRouteStillCurrent = true,
                routeActivationPending = true,
                browserHadFocusBeforeRender = true,
                browserHasFocus = false,
                anotherVisibleActionHasFocus = true,
            ),
        )
    }
}
