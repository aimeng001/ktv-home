package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskBackNavigationPolicyTest {

    @Test
    fun whenInInnerDetailBackNavigatesOutOfInnerDetail() {
        val action = KioskBackNavigationPolicy.resolveBrowserBack(hasInnerDetailBack = true)
        assertEquals(KioskBrowserBackAction.LEAVE_INNER_DETAIL, action)
    }

    @Test
    fun whenNotInInnerDetailBackReturnsToDashboard() {
        val action = KioskBackNavigationPolicy.resolveBrowserBack(hasInnerDetailBack = false)
        assertEquals(KioskBrowserBackAction.RETURN_TO_DASHBOARD, action)
    }

    @Test
    fun resolveBrowserBackTargetPreservesSearchOriginOnlyWhenLeavingBrowser() {
        assertEquals(
            KioskBrowserReturnTarget.INNER_DETAIL,
            KioskBackNavigationPolicy.resolveBrowserBackTarget(
                hasInnerDetailBack = true,
                hasPendingSearchReturnFocus = true,
            ),
        )
        assertEquals(
            KioskBrowserReturnTarget.SEARCH_ORIGIN,
            KioskBackNavigationPolicy.resolveBrowserBackTarget(
                hasInnerDetailBack = false,
                hasPendingSearchReturnFocus = true,
            ),
        )
        assertEquals(
            KioskBrowserReturnTarget.DASHBOARD,
            KioskBackNavigationPolicy.resolveBrowserBackTarget(
                hasInnerDetailBack = false,
                hasPendingSearchReturnFocus = false,
            ),
        )
    }
}
