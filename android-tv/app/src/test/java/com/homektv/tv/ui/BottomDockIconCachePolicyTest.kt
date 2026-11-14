package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BottomDockIconCachePolicyTest {

    @Test
    fun emitsIconUpdateOnlyWhenResourceChanges() {
        val policy = BottomDockIconCachePolicy()

        assertTrue(policy.shouldUpdateVocalIcon(101))
        assertFalse(policy.shouldUpdateVocalIcon(101))
        assertTrue(policy.shouldUpdateVocalIcon(102))

        assertTrue(policy.shouldUpdatePlayPauseIcon(201))
        assertFalse(policy.shouldUpdatePlayPauseIcon(201))
        assertTrue(policy.shouldUpdatePlayPauseIcon(202))
    }

    @Test
    fun dockControllerSkipsAllButtonNormalizationOnEveryRenderUpdatesExternalDisplayVisibilityAndClearsVolumeListeners() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()

        val renderMethod = source.substringAfter("fun render(")
            .substringBefore("fun setQrBitmap(")
        assertTrue(
            "render() must gate drawable rebinding and normalization via BottomDockIconCachePolicy",
            renderMethod.contains("iconCachePolicy.shouldUpdateVocalIcon(") &&
                renderMethod.contains("iconCachePolicy.shouldUpdatePlayPauseIcon(") &&
                renderMethod.contains("if (vocalIconChanged || playPauseIconChanged)"),
        )

        val externalDisplayMethod = source.substringAfter("fun setExternalDisplayActive(")
            .substringBefore("fun ensurePlayerInDock(")
        assertTrue(
            "setExternalDisplayActive() must refresh inline dock player visibility when not fullscreen",
            externalDisplayMethod.contains("updateContainerVisibility()"),
        )

        val destroyMethod = source.substringAfter("fun destroy(")
            .substringBefore("private fun applyWindowBounds(")
        assertTrue(
            "destroy() must clear volume button click listeners",
            destroyMethod.contains("btnDockVolDown.setOnClickListener(null)") &&
                destroyMethod.contains("btnDockVolUp.setOnClickListener(null)"),
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
