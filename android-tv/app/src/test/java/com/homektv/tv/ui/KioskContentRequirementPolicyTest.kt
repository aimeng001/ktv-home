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
        assertEquals(140, qwerty.minimumHeightDp)
        assertEquals(374, t9.preferredHeightDp)
        assertEquals(148, t9.minimumHeightDp)
    }

    @Test
    fun pinyinMinimumHeightPreservesResultsAndScrollableKeyboardWhileAllowingPip() {
        val requirement = KioskContentRequirementPolicy.pinyin(
            keyboardNaturalHeightDp = 350,
            resultsMinimumHeightDp = 184,
            minimumScrollableViewportDp = 140,
        )

        assertEquals(350, requirement.preferredHeightDp)
        assertEquals(184, requirement.minimumHeightDp)
    }

    @Test
    fun listPageReservesVisibleChromeAndOneCompleteItemRow() {
        val requirement = KioskContentRequirementPolicy.listPage(
            chromeHeightDp = 112,
            firstItemRowHeightDp = 92,
            minimumScrollableViewportDp = 168,
        )

        assertEquals(280, requirement.preferredHeightDp)
        assertEquals(280, requirement.minimumHeightDp)
    }

    @Test
    fun statusOnlyPageStillKeepsItsVisibleErrorOrEmptyAction() {
        val requirement = KioskContentRequirementPolicy.listPage(
            chromeHeightDp = 146,
            firstItemRowHeightDp = 74,
            minimumScrollableViewportDp = 74,
        )

        assertEquals(220, requirement.preferredHeightDp)
        assertEquals(220, requirement.minimumHeightDp)
    }

    @Test
    fun dashboardRequiresBothActionRowsAndTheirMeasuredChrome() {
        val requirement = KioskContentRequirementPolicy.dashboard(
            topRowHeightDp = 80,
            bottomRowHeightDp = 80,
            interRowAndOuterSpacingDp = 22,
        )

        assertEquals(182, requirement.preferredHeightDp)
        assertEquals(182, requirement.minimumHeightDp)
    }
}
