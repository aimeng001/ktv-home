package com.homektv.tv.ui.kiosk

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardBackgroundAssetContractTest {
    @Test
    fun dashboardBackdropIsAStandaloneHighResolutionLandscapeArtwork() {
        val artwork = locate("src/main/res/drawable-nodpi/ktv_dashboard_background_blue_stage.png")
        assertTrue("dashboard background artwork must be packaged in the app", artwork.isFile)

        val png = artwork.readBytes()
        assertTrue("dashboard background must be a valid PNG", png.size >= 24 &&
            png.take(8).map { it.toInt() and 0xFF } == listOf(137, 80, 78, 71, 13, 10, 26, 10))
        val width = png.readPngInt(16)
        val height = png.readPngInt(20)
        assertTrue("background should be large enough for 1080p TV output", width >= 1600)
        assertTrue("background should be large enough for 1080p TV output", height >= 900)
        assertTrue(
            "background should use a 16:9 composition for TV screens",
            kotlin.math.abs(width.toDouble() / height - 16.0 / 9.0) < 0.02,
        )
    }

    @Test
    fun runtimeBackdropDrawsTheArtworkWithCenterCropAndAReadabilityScrim() {
        val implementation = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvDashboardBackground.kt").readText()
        assertTrue("runtime must load the selected blue-stage dashboard artwork", implementation.contains("R.drawable.ktv_dashboard_background_blue_stage"))
        assertTrue("artwork must be cropped to the view bounds without distortion", implementation.contains("canvas.drawBitmap"))
        assertTrue("artwork must be dimmed consistently behind the live UI", implementation.contains("scrimPaint"))
        assertTrue(
            "portrait screens must retain a stage-edge crop instead of the empty center strip",
            implementation.contains("PORTRAIT_CROP_MAX_ASPECT"),
        )
        assertTrue("the existing graphite renderer must remain as an image-load fallback", implementation.contains("ReferenceGraphiteDrawable"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: workingDirectory.resolve("app/$relativePath")
    }

    private fun ByteArray.readPngInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}
