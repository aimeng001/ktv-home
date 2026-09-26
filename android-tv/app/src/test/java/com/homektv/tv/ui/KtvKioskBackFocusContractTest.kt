package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskBackFocusContractTest {
    @Test
    fun returningFromCategorySongsRestoresFocusToActiveDirectoryMode() {
        val source = listOf(
            File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Cannot locate kiosk controller")
        val resetCategoryDetail = source.substringAfter("private fun resetCategoryDetail()")
            .substringBefore("private fun resetInnerDetail()")

        assertTrue(resetCategoryDetail.contains("categoryFocusRestorePending = true"))
    }

    @Test
    fun categoryReturnWaitsForAsyncDirectoryListToCommitBeforeRestoringFocus() {
        val source = listOf(
            File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Cannot locate kiosk controller")
        val focusRestore = source.substringAfter("private fun restoreCategoryModeFocusIfReady(")
            .substringBefore("private fun resetInnerDetail()")

        assertTrue(source.contains("categoryNameAdapter.submitList(state.languages) {"))
        assertTrue(source.contains("categoryNameAdapter.submitList(state.tags) {"))
        assertTrue(focusRestore.contains("state.catalogLoading"))
        assertTrue(focusRestore.contains("binding.kioskOverlay.btnCategoryLanguages"))
        assertTrue(focusRestore.contains("binding.kioskOverlay.btnCategoryTags"))
        assertTrue(focusRestore.contains("target.requestFocus()"))
    }
}
