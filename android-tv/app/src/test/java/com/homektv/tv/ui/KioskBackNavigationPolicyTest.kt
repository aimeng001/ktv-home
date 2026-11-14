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
}
