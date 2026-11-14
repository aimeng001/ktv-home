package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrowserRailSyncPolicyTest {

    @Test
    fun reusesExistingButtonsWhenLabelsMatchAndSelectionChanges() {
        val existingLabels = listOf("返回歌单列表", "经典老歌  20首", "热门新歌  15首")
        val next = listOf(
            BrowserRailOptionSpec("返回歌单列表", selected = false) {},
            BrowserRailOptionSpec("经典老歌  20首", selected = true) {},
            BrowserRailOptionSpec("热门新歌  15首", selected = false) {},
        )

        assertTrue(BrowserRailSyncPolicy.canReuseButtons(existingLabels, next))
    }

    @Test
    fun rebuildsWhenLabelsOrOrderChange() {
        val existingLabels = listOf("华语  100", "粤语  50")
        val changedLabel = listOf(
            BrowserRailOptionSpec("华语  101", selected = false) {},
            BrowserRailOptionSpec("粤语  50", selected = false) {},
        )
        val changedSize = listOf(
            BrowserRailOptionSpec("华语  100", selected = false) {},
        )

        assertFalse(BrowserRailSyncPolicy.canReuseButtons(existingLabels, changedLabel))
        assertFalse(BrowserRailSyncPolicy.canReuseButtons(existingLabels, changedSize))
    }

    @Test
    fun kioskOverlayControllerDoesNotUnconditionallyClearOptionRailsDuringBrowserRender() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderMethod = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun isThreeColumnTab")
        val initialMethod = source.substringAfter("private fun renderSingerInitialRail")
            .substringBefore("private fun addBrowserFilterOption")

        assertFalse(
            "renderThreeColumnBrowser must incrementally sync filter options instead of unconditional removeAllViews()",
            renderMethod.contains("browser.browserFilterOptions.removeAllViews()"),
        )
        assertFalse(
            "renderSingerInitialRail must incrementally sync A-Z buttons instead of unconditional rail.removeAllViews()",
            initialMethod.contains("rail.removeAllViews()"),
        )
    }

    private fun locate(relativePath: String): File {
        val candidates = sequenceOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath")
    }
}
