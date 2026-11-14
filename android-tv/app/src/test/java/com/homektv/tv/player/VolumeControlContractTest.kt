package com.homektv.tv.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeControlContractTest {
    @Test
    fun playerSoftwareVolumeScalesOnlyWhenHardwareVolumeIsFixedOrMuted() {
        assertEquals(0f, resolvePlayerSoftwareVolume(volumePercent = 80, muted = true, isVolumeFixed = false), 0.0001f)
        assertEquals(0f, resolvePlayerSoftwareVolume(volumePercent = 80, muted = true, isVolumeFixed = true), 0.0001f)
        assertEquals(1f, resolvePlayerSoftwareVolume(volumePercent = 25, muted = false, isVolumeFixed = false), 0.0001f)
        assertEquals(0.45f, resolvePlayerSoftwareVolume(volumePercent = 45, muted = false, isVolumeFixed = true), 0.0001f)
        assertEquals(1f, resolvePlayerSoftwareVolume(volumePercent = 120, muted = false, isVolumeFixed = true), 0.0001f)
        assertEquals(0f, resolvePlayerSoftwareVolume(volumePercent = -10, muted = false, isVolumeFixed = true), 0.0001f)
    }

    @Test
    fun bottomDockVolumeButtonsRouteThroughAppVolumeControlInsteadOfDirectAdjustStreamVolume() {
        val dock = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        assertFalse(dock.contains("adjustStreamVolume("))
        assertTrue(dock.contains("onVolumeDelta"))

        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(main.contains("onVolumeDelta = ::changeVolume"))
        val changeVolumeBody = main.substringAfter("private fun changeVolume(delta: Int)")
            .substringBefore("\n    private fun ")
        assertTrue(changeVolumeBody.contains("currentVolume = volume"))
        assertTrue(changeVolumeBody.contains("engine?.applyVolume(volume, currentMuted)"))
        assertTrue(changeVolumeBody.contains("showVolumeOsd(volume, currentMuted)"))

        val engine = locate("src/main/java/com/homektv/tv/player/PlaybackEngine.kt").readText()
        assertTrue(engine.contains("audioManager.isVolumeFixed"))
        assertTrue(engine.contains("resolvePlayerSoftwareVolume("))
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val localCandidate = File(current, relativePath)
            if (localCandidate.isFile) return localCandidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
