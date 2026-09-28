package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPipResponsivePolicyTest {
    @Test
    fun tvLayoutMatchesReferenceHeightWhileKeepingSixteenByNine() {
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 1232,
            availableHeightDp = 480,
            minContentHeightDp = 134,
        )

        assertEquals(271, size.widthDp)
        assertTrue(size.visible)
    }

    @Test
    fun veryShortStageHidesPipBeforeItLeavesLessThanOneKeyboardRow() {
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 592,
            availableHeightDp = 160,
            minContentHeightDp = 134,
        )

        assertEquals(0, size.widthDp)
        assertFalse(size.visible)
    }

    @Test
    fun enoughHeightKeepsPipAndReservesOneKeyboardActionRow() {
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 592,
            availableHeightDp = 230,
            minContentHeightDp = 134,
        )

        assertEquals(130, size.widthDp)
        assertTrue(size.visible)
    }

    @Test
    fun stageTooShortForPipAndOneContentRowHidesPip() {
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 272,
            availableHeightDp = 130,
            minContentHeightDp = 134,
        )

        assertEquals(0, size.widthDp)
        assertFalse(size.visible)
    }

    @Test
    fun fullKeyboardRequirementHidesPipWhenStageCannotFitItsHeight() {
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 900,
            availableHeightDp = 360,
            minContentHeightDp = 350,
        )

        assertEquals(0, size.widthDp)
        assertFalse(size.visible)
    }

    @Test
    fun pipVisibilityChangesAtTheExactFullContentBoundary() {
        val exactlyEnough = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 592,
            availableHeightDp = 440,
            minContentHeightDp = 350,
        )
        val oneDpTooShort = KioskPipResponsivePolicy.resolve(
            availableWidthDp = 592,
            availableHeightDp = 439,
            minContentHeightDp = 350,
        )

        assertEquals(128, exactlyEnough.widthDp)
        assertTrue(exactlyEnough.visible)
        assertEquals(0, oneDpTooShort.widthDp)
        assertFalse(oneDpTooShort.visible)
    }
}
