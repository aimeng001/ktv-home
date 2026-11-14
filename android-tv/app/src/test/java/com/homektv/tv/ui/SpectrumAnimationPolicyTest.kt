package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectrumAnimationPolicyTest {

    @Test
    fun stopsSchedulingWhenParentOrWindowHidesViewOrDetaches() {
        assertFalse(SpectrumAnimationPolicy.shouldScheduleNextFrame(isShown = false, isAttachedToWindow = true))
        assertFalse(SpectrumAnimationPolicy.shouldScheduleNextFrame(isShown = true, isAttachedToWindow = false))
        assertTrue(SpectrumAnimationPolicy.shouldScheduleNextFrame(isShown = true, isAttachedToWindow = true))
    }

    @Test
    fun kicksAnimationOnlyWhenShownAndAttached() {
        assertFalse(SpectrumAnimationPolicy.shouldKickAnimation(isShown = false, isAttachedToWindow = true))
        assertFalse(SpectrumAnimationPolicy.shouldKickAnimation(isShown = true, isAttachedToWindow = false))
        assertTrue(SpectrumAnimationPolicy.shouldKickAnimation(isShown = true, isAttachedToWindow = true))
    }

    @Test
    fun bothSpectrumViewsGateFramesWithIsShownAndResumeOnVisibilityRestore() {
        listOf("AudioSpectrumView.kt", "KtvMiniSpectrumView.kt").forEach { fileName ->
            val source = locateUiFile(fileName).readText()
            assertTrue(
                "$fileName must use SpectrumAnimationPolicy in onDraw",
                source.contains("SpectrumAnimationPolicy.shouldScheduleNextFrame("),
            )
            assertTrue(
                "$fileName must resume animation in onVisibilityChanged",
                source.contains("override fun onVisibilityChanged("),
            )
            assertTrue(
                "$fileName must resume animation in onWindowVisibilityChanged",
                source.contains("override fun onWindowVisibilityChanged("),
            )
            assertTrue(
                "$fileName must handle window attach lifecycle",
                source.contains("override fun onAttachedToWindow("),
            )
        }
    }

    private fun locateUiFile(name: String): File {
        val candidates = sequenceOf(
            File("src/main/java/com/homektv/tv/ui/$name"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/$name"),
            File("../app/src/main/java/com/homektv/tv/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $name")
    }
}
