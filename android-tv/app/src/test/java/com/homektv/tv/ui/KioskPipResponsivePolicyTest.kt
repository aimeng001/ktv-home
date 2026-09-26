package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPipResponsivePolicyTest {
    @Test
    fun tvLayoutMatchesReferenceHeightWhileKeepingSixteenByNine() {
        val size = KioskPipResponsivePolicy.resolve(availableWidthDp = 1232, availableHeightDp = 480)

        assertEquals(271, size.widthDp)
        assertTrue(size.visible)
    }

    @Test
    fun veryShortStageHidesPipBeforeItLeavesLessThanOneKeyboardRow() {
        val size = KioskPipResponsivePolicy.resolve(availableWidthDp = 592, availableHeightDp = 160)

        assertEquals(0, size.widthDp)
        assertFalse(size.visible)
    }

    @Test
    fun enoughHeightKeepsPipAndReservesOneKeyboardActionRow() {
        val size = KioskPipResponsivePolicy.resolve(availableWidthDp = 592, availableHeightDp = 230)

        assertEquals(130, size.widthDp)
        assertTrue(size.visible)
    }

    @Test
    fun stageTooShortForPipAndOneContentRowHidesPip() {
        val size = KioskPipResponsivePolicy.resolve(availableWidthDp = 272, availableHeightDp = 130)

        assertEquals(0, size.widthDp)
        assertFalse(size.visible)
    }
}
