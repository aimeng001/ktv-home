package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvCategoryRoutePolicyTest {
    @Test
    fun dashboardCategoryOpensTagSelectionInsteadOfNewestSongs() {
        assertEquals(KtvCategoryRoute.TAGS, KtvCategoryRoutePolicy.dashboardCategory())
    }
}
