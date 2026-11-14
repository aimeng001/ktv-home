package com.homektv.tv.ui.kiosk

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class KtvKioskGhostUpdateTest {
    @Test
    fun legacyAdaptersAndPanelsStayDeletedFromOverlayController() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertFalse(source.contains("KtvKioskSongAdapter"))
        assertFalse(source.contains("panelPinyin"))
        assertFalse(source.contains("panelCategories"))
    }

    @Test
    fun kioskFocusRestorationTargetsVisibleThreeColumnComponents() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val applyActiveMethod = source.substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringBefore("fun toggleKiosk")

        assertFalse(
            "点歌台激活时焦点不能请求不可见的旧顶栏 tabPinyin",
            applyActiveMethod.contains("tabPinyin"),
        )
        assertFalse(
            "点歌台激活时焦点不能请求不可见的旧顶栏 tabSingers",
            applyActiveMethod.contains("tabSingers"),
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
