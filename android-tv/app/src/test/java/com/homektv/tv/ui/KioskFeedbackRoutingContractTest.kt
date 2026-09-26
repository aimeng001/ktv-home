package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskFeedbackRoutingContractTest {
    @Test
    fun toastIsShownOnlyForTransientFeedbackAndPageErrorsUseAnInlineRoute() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val transientBranch = source.substringAfter("KioskFeedbackDestination.TRANSIENT ->")
            .substringBefore("KioskFeedbackDestination.NONE")

        assertTrue(source.contains("KioskFeedbackRoutingPolicy.resolve("))
        assertTrue(transientBranch.contains("Toast.makeText(activity, feedback, Toast.LENGTH_SHORT).show()"))
        assertTrue(source.contains("KioskFeedbackDestination.INLINE -> Unit"))
        assertTrue(source.contains("private fun inlineFeedbackForCurrentPage(state: ControllerUiState)"))
        assertTrue(source.contains("state.error === domainError"))
    }

    @Test
    fun inlineFeedbackIsLimitedToPagesThatActuallyRenderTheMatchingError() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
            .substringAfter("private fun inlineFeedbackForCurrentPage")
            .substringBefore("private fun renderCatalogState")

        listOf(
            "KioskTab.CATEGORIES",
            "KioskTab.SINGERS",
            "KioskTab.FAVORITES",
            "KioskTab.HISTORY",
            "KioskTab.PLAYLISTS",
            "UiDomain.CATALOG",
            "UiDomain.FAVORITES",
            "UiDomain.HISTORY",
        ).forEach { assertTrue("Missing inline feedback route: $it", source.contains(it)) }
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
