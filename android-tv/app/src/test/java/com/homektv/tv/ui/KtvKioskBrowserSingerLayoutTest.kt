package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskBrowserSingerLayoutTest {

    @Test
    fun singerGridUsesFiveColumnsAndTenItemsPerPage() {
        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        // Verify span count is 5
        assertTrue(
            "browserSingerRecyclerView must use GridLayoutManager with 5 columns",
            controllerSource.contains("browserSingerRecyclerView.layoutManager = GridLayoutManager(activity, 5)"),
        )

        // Verify page size is 10 (2 rows x 5 columns)
        assertTrue(
            "SINGER_GRID_PAGE_SIZE must be 10 for 2x5 grid",
            controllerSource.contains("const val SINGER_GRID_PAGE_SIZE = 10"),
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
            "Avatar width must be 96dp",
            layoutSource.contains("android:layout_width=\"96dp\""),
        )
        assertTrue(
            "Avatar height must be 96dp",
            layoutSource.contains("android:layout_height=\"96dp\""),
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
