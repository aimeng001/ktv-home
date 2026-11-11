package com.homektv.tv.ui.kiosk

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class KtvKioskGhostUpdateTest {
    @Test
    fun observeStateDoesNotDispatchRedundantDataToInvisibleSearchAdapter() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val observeStateMethod = source.substringAfter("private fun observeState()")
            .substringBefore("renderSearchEmptyState()")

        assertFalse(
            "商业三栏点歌台已激活时，observeState 不应向旧 searchAdapter 频繁 submitList 浪费 CPU",
            observeStateMethod.contains("searchAdapter.submitList(state.results)"),
        )
    }

    @Test
    fun kioskDoesNotSubmitListToDetachedAdaptersInThreeColumnMode() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderCatalog = source.substringAfter("private fun renderCatalogState(state: ControllerUiState)")
            .substringBefore("private fun renderThreeColumnBrowser")

        assertFalse(
            "三栏模式下旧 rankingAdapter 不应再被无条件更新",
            renderCatalog.contains("rankingAdapter.submitList(state.ranking)") &&
                !renderCatalog.contains("if (!isThreeColumnTab") &&
                !renderCatalog.contains("if (isThreeColumnTab"),
        )
    }

    @Test
    fun kioskFocusRestorationTargetsVisibleThreeColumnComponents() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val applyActiveMethod = source.substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringBefore("fun toggleKiosk")

        assertFalse(
            "点歌台激活时焦点不能请求不可见的旧顶栏 tabPinyin",
            applyActiveMethod.contains("binding.kioskOverlay.tabPinyin.requestFocus()"),
        )
        assertFalse(
            "点歌台激活时焦点不能请求不可见的旧顶栏 tabSingers",
            applyActiveMethod.contains("binding.kioskOverlay.tabSingers.requestFocus()"),
        )
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequence {
            yield(workingDirectory.resolve(relativePath))
            yield(workingDirectory.resolve("app/$relativePath"))
            yield(workingDirectory.resolve("../$relativePath"))
            yield(workingDirectory.resolve("../../$relativePath"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
