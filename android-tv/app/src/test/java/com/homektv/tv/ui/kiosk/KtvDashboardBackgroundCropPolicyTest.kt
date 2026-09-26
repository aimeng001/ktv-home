package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvDashboardBackgroundCropPolicyTest {
    @Test
    fun portraitViewportKeepsTheStageEdgeWithoutStretchingTheArtwork() {
        val crop = KtvDashboardBackground.sourceCropWindow(
            imageWidth = 1672,
            imageHeight = 941,
            viewportWidth = 1080,
            viewportHeight = 2400,
        )

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(423, crop.right - crop.left)
        assertEquals(941, crop.bottom - crop.top)
    }

    @Test
    fun nonPortraitViewportContinuesToUseTheCenteredCrop() {
        val crop = KtvDashboardBackground.sourceCropWindow(
            imageWidth = 1672,
            imageHeight = 941,
            viewportWidth = 1600,
            viewportHeight = 1080,
        )

        assertEquals(139, crop.left)
        assertEquals(1533, crop.right)
        assertEquals(0, crop.top)
        assertEquals(941, crop.bottom)
    }
}
