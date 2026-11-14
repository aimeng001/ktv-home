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
        assertTrue(resetToSingers.contains("browserBinding.browserNavSingers.post"))
        assertTrue(!resetToSingers.contains("btnSingerAll.requestFocus()"))
    }

    @Test
    fun categoryRootCacheIsUsedOnlyWhileItsBackingDataStillExists() {
        val source = controllerSource()
        val tabSelection = source.substringAfter("private fun applyTabSelection(")
            .substringBefore("private fun onKeywordInput(")
        val rootSelection = source.substringAfter("private fun openCategoryRoot(")
            .substringBefore("private fun onArtistSelected(")
        val dataCheck = source.substringAfter("private fun hasCategoryRootData(")
            .substringBefore("private fun loadCategories(")

        assertTrue(tabSelection.contains("shouldLoadCategoryRoot(categoryMode)"))
        assertTrue(rootSelection.contains("shouldLoadCategoryRoot(mode)"))
        assertTrue(source.contains("controllerState.catalogLoading && lastRequestedCategoryMode == mode"))
        assertTrue(dataCheck.contains("KioskCategoryMode.NEW -> controllerState.newSongs.isNotEmpty()"))
        assertTrue(dataCheck.contains("KioskCategoryMode.LANGUAGES -> controllerState.languages.isNotEmpty()"))
        assertTrue(dataCheck.contains("KioskCategoryMode.TAGS -> controllerState.tags.isNotEmpty()"))
    }

    private fun controllerSource(): String = listOf(
        File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Cannot locate kiosk controller")
}
