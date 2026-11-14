package com.homektv.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityExternalDisplayContractTest {

    @Test
    fun mainActivityOwnsPresentationLifecycleAndReattachesMainSurface() {
        val source = locate("MainActivity.kt").readText()

        assertTrue(source.contains("KtvPresentationController(this)"))
        assertTrue(source.contains("presentationController?.stop()"))
        assertTrue(source.contains("attachedExternalPlayerView?.let { engine?.detach(it) }"))
        assertTrue(source.contains("engine?.attach(externalPlayerView)"))
        assertTrue(source.contains("engine?.attach(bottomDockController.playerView)"))
        assertTrue(source.contains("bottomDockController.setExternalDisplayActive(true)"))
        assertTrue(source.contains("bottomDockController.setExternalDisplayActive(false)"))
        val disconnectBranch = source.substringAfter("KtvPresentationController(this)")
            .substringAfter("externalDisplayActive = false")
            .substringBefore("} else {")
        assertTrue(
            "Returning from external HDMI display must restore saved video scale mode",
            disconnectBranch.contains("applyVideoScaleMode(currentVideoScaleMode)"),
        )
    }

    private fun locate(name: String): File {
        val candidates = sequenceOf(
            File("src/main/java/com/homektv/tv/ui/$name"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/$name"),
            File("../app/src/main/java/com/homektv/tv/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $name from ${File(".").absolutePath}")
    }
}
