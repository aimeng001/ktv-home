package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskBrowserSingerLayoutTest {

    @Test
    fun singerGridUsesEightColumnsAndSixteenItemsPerPage() {
        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        // Verify span count is 8
        assertTrue(
            "browserSingerRecyclerView must use GridLayoutManager with 8 columns",
            controllerSource.contains("browserSingerRecyclerView.layoutManager = GridLayoutManager(activity, 8)"),
        )

        // Verify page size is 16 (2 rows x 8 columns)
        assertTrue(
            "SINGER_GRID_PAGE_SIZE must be 16 for 2x8 grid",
            controllerSource.contains("const val SINGER_GRID_PAGE_SIZE = 16"),
        )
    }

    @Test
    fun artistBatchSizeIsAlignedToSixteenForSingerPages() {
        val vmSource = locate("src/main/java/com/homektv/tv/controller/ControllerViewModel.kt").readText()

        assertTrue(
            "ControllerViewModel must request artist batches aligned to 16 (32 = 2 pages)",
            vmSource.contains("const val ARTIST_GRID_PAGE_SIZE = 32"),
        )
        assertTrue(
            "loadArtistsPage must pass size = ARTIST_GRID_PAGE_SIZE",
            vmSource.contains("size = ARTIST_GRID_PAGE_SIZE"),
        )
    }

    @Test
    fun singerCardUsesCircleAvatarAndCompactDimensions() {
        val layoutSource = locate("src/main/res/layout/item_singer_card.xml").readText()

        assertTrue(
            "item_singer_card must use circular avatar background",
            layoutSource.contains("@drawable/bg_avatar_circle"),
        )
        assertTrue(
            "Avatar width must be 72dp for 8-column grid",
            layoutSource.contains("android:layout_width=\"72dp\""),
        )
        assertTrue(
            "Avatar height must be 72dp for 8-column grid",
            layoutSource.contains("android:layout_height=\"72dp\""),
        )
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val candidateDirect = File(current, relativePath)
            if (candidateDirect.isFile) return candidateDirect
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
