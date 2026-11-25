package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskFeedbackRoutingContractTest {
    @Test
    fun transientFeedbackUsesAnOverlayHostAndPageErrorsUseAnInlineRoute() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val transientBranch = source.substringAfter("KioskFeedbackDestination.TRANSIENT ->")
            .substringBefore("KioskFeedbackDestination.NONE")

        assertTrue(source.contains("KioskFeedbackRoutingPolicy.resolve("))
        assertTrue(transientBranch.contains("transientFeedbackHost.show(feedback)"))
        assertFalse(transientBranch.contains("Toast.makeText(activity, feedback, Toast.LENGTH_SHORT).show()"))
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

    @Test
    fun earlyDashboardAndDrawerPathsGuardLazyBrowserBindingBeforeAccess() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("private fun initializedBrowserBindingOrNull()"))
        listOf(
            "private fun inlineFeedbackForCurrentPage" to "private fun renderCatalogState",
            "private fun isSearchKeyboardShowing" to "private fun applyKeyboardVisibility",
            "private fun applyKeyboardVisibility" to "private fun showBrowserRetry",
            "private fun scrollResultsPage" to "private fun currentSongsHasMore",
            "private fun captureCurrentNavigationEntry" to "private fun pushNavigationSnapshot",
        ).forEach { (startMarker, endMarker) ->
            val block = source.substringAfter(startMarker).substringBefore(endMarker)
            assertFalse(
                "Method $startMarker must not access raw browserBinding before checking initialization",
                block.contains("browserBinding."),
            )
            assertTrue(
                "Method $startMarker should guard lazy browserBinding via initializedBrowserBindingOrNull()",
                block.contains("initializedBrowserBindingOrNull()"),
            )
        }

        val renderBrowserBlock = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun showVarietyPanel")
        val earlyReturnIdx = renderBrowserBlock.indexOf("if (!isThreeColumnTab(tab)) return")
        val ensureIdx = renderBrowserBlock.indexOf("ensureBrowserBinding()")
        assertTrue(earlyReturnIdx >= 0 && ensureIdx > earlyReturnIdx)
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
