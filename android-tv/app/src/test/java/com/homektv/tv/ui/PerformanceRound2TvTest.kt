package com.homektv.tv.ui

import com.homektv.tv.ui.kiosk.SongDisplayFormatter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceRound2TvTest {

    @Test
    fun weightedLruCache_trimToBytesEvictsLeastRecentlyUsedEntries() {
        val cache = WeightedLruCache<String, ByteArray>(
            capacity = 10,
            maxBytes = 1_000L,
            weight = { it.size.toLong() },
        )
        cache.put("a", ByteArray(300))
        cache.put("b", ByteArray(300))
        cache.put("c", ByteArray(300))
        // Touch "a" so "b" is now least recently used.
        assertNotNull(cache["a"])

        cache.trimToBytes(600L)
        assertEquals(600L, cache.byteSize)
        assertNull(cache["b"])
        assertNotNull(cache["a"])
        assertNotNull(cache["c"])
    }

    @Test
    fun songDisplayFormatterFastPathMatchesExpectedFormattingAcrossCleanAndDirtyInputs() {
        assertEquals("晴天", SongDisplayFormatter.formatTitle("  晴天  "))
        assertEquals("Love Story", SongDisplayFormatter.formatTitle("Love Story"))
        assertEquals("十年", SongDisplayFormatter.formatTitle("十年[1080P][原版]"))
        assertEquals("周杰伦", SongDisplayFormatter.formatArtist("周杰伦"))
        assertEquals("邓紫棋/许佳麟", SongDisplayFormatter.formatArtist("邓紫棋 / 许佳麟"))
        assertEquals("周杰伦/费玉清", SongDisplayFormatter.formatArtist("周杰伦_费玉清"))
        assertEquals("再见 - 朋友", SongDisplayFormatter.formatTitle("  - 再见  -  朋友 -  "))
    }

    @Test
    fun round2TvRenderingAndLifecycleContractsArePreserved() {
        val lyricSource = locate("src/main/java/com/homektv/tv/ui/KaraokeLyricView.kt").readText()
        assertTrue(lyricSource.contains("rebuildLayoutCache("))
        val onDrawBody = lyricSource.substringAfter("override fun onDraw(canvas: Canvas)")
            .substringBefore("private fun buildAnchors(")
        assertFalse("onDraw must not call measureText per frame", onDrawBody.contains("measureText("))

        val spectrumSource = locate("src/main/java/com/homektv/tv/ui/AudioSpectrumView.kt").readText()
        assertTrue(spectrumSource.contains("postInvalidateDelayed(33L)"))
        assertTrue(spectrumSource.contains("val count = 48"))

        val miniSpectrumSource = locate("src/main/java/com/homektv/tv/ui/KtvMiniSpectrumView.kt").readText()
        assertTrue(miniSpectrumSource.contains("postInvalidateDelayed(33L)"))

        val mainActivitySource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(mainActivitySource.contains("override fun onTrimMemory(level: Int)"))
        assertTrue(mainActivitySource.contains("findLyricIndex(lyricLines, positionMs)"))
        assertTrue(mainActivitySource.contains("private val clockTimeFormat by lazy"))

        val overlaySource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(overlaySource.contains("private fun ensureBrowserBinding()"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
