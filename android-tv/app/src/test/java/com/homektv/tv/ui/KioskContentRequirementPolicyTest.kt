package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskContentRequirementPolicyTest {
    @Test
    fun pinyinRequirementUsesNaturalQwertyOrT9HeightAndKeepsScrollableFallbackSeparate() {
        val qwerty = KioskContentRequirementPolicy.pinyin(
            keyboardNaturalHeightDp = 350,
            resultsMinimumHeightDp = 84,
            minimumScrollableViewportDp = 140,
        )
        val t9 = KioskContentRequirementPolicy.pinyin(
            keyboardNaturalHeightDp = 374,
            resultsMinimumHeightDp = 84,
            minimumScrollableViewportDp = 148,
        )

        assertEquals(350, qwerty.preferredHeightDp)
        assertEquals(140, qwerty.minimumScrollableViewportDp)
        assertEquals(374, t9.preferredHeightDp)
        assertEquals(148, t9.minimumScrollableViewportDp)
    }

    @Test
    fun listPageReservesVisibleChromeAndOneCompleteItemRow() {
        val requirement = KioskContentRequirementPolicy.listPage(
            chromeHeightDp = 112,
            firstItemRowHeightDp = 92,
            minimumScrollableViewportDp = 168,
        )

        assertEquals(204, requirement.preferredHeightDp)
        assertEquals(168, requirement.minimumScrollableViewportDp)
    }

    @Test
    fun statusOnlyPageStillKeepsItsVisibleErrorOrEmptyAction() {
        val requirement = KioskContentRequirementPolicy.listPage(
            chromeHeightDp = 146,
            firstItemRowHeightDp = 0,
            minimumScrollableViewportDp = 120,
        )

        assertEquals(146, requirement.preferredHeightDp)
        assertEquals(120, requirement.minimumScrollableViewportDp)
    }

    @Test
    fun dashboardRequiresBothActionRowsAndTheirMeasuredChrome() {
        val requirement = KioskContentRequirementPolicy.dashboard(
            topRowHeightDp = 80,
            bottomRowHeightDp = 80,
            interRowAndOuterSpacingDp = 22,
        )

        assertEquals(182, requirement.preferredHeightDp)
        assertEquals(80, requirement.minimumScrollableViewportDp)
    }
}
