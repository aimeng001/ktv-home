package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvSettingsLayoutPolicyTest {
    @Test
    fun reference1080CanvasUsesMeasuredSettingsGeometry() {
        val metrics = KtvSettingsLayoutPolicy.forScreenHeight(1080)

        assertEquals(100, metrics.rowHeightPx)
        assertEquals(22, metrics.rowGapPx)
        assertEquals(30, metrics.horizontalInsetPx)
        assertEquals(20, metrics.columnGapPx)
        assertEquals(-72, metrics.translationYPx)
    }

    @Test
    fun compact720CanvasFitsAllSixRowsBetweenHeaderAndDock() {
        val metrics = KtvSettingsLayoutPolicy.forScreenHeight(720)
        val gridHeight = 6 * metrics.rowHeightPx + 5 * metrics.rowGapPx
        val gridTop = (720 - gridHeight) / 2 + metrics.translationYPx
        val gridBottom = gridTop + gridHeight

        assertEquals(67, metrics.rowHeightPx)
        assertEquals(15, metrics.rowGapPx)
        assertEquals(20, metrics.horizontalInsetPx)
        assertEquals(13, metrics.columnGapPx)
        assertEquals(-48, metrics.translationYPx)
        assertTrue("First row must start below the 72px top bar", gridTop >= 72)
        assertTrue("Sixth row must end above the 564px dock boundary", gridBottom <= 564)
    }
}
