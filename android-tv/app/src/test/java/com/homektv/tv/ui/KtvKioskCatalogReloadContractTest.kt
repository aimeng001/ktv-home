package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskCatalogReloadContractTest {
    @Test
    fun singerTabReloadsWhenSharedCatalogStateWasCleared() {
        val source = controllerSource()
        val tabSelection = source.substringAfter("private fun applyTabSelection(")
            .substringBefore("private fun onKeywordInput(")

        assertTrue(
            tabSelection.contains(
                "KioskTab.SINGERS -> if (!artistsLoaded || controllerState.artists.isEmpty()) loadArtists()",
            ),
        )
    }

    @Test
    fun returningFromArtistDetailReloadsWhenSingerTabWasAlreadySelected() {
        val source = controllerSource()
        val resetToSingers = source.substringAfter("private fun resetToSingersTab()")
            .substringBefore("private fun resetCategoryDetail()")

        assertTrue(resetToSingers.contains("wasAlreadyOnSingersTab"))
        assertTrue(resetToSingers.contains("if (wasAlreadyOnSingersTab) loadArtists()"))
        assertTrue(resetToSingers.contains("artistsLoaded = false"))
        assertTrue(resetToSingers.contains("btnSingerAll.requestFocus()"))
    }

    private fun controllerSource(): String = listOf(
        File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Cannot locate kiosk controller")
}
