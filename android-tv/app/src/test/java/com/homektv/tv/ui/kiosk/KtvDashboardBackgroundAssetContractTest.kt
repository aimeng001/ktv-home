package com.homektv.tv.ui.kiosk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardBackgroundAssetContractTest {
    @Test
    fun dashboardBackdropIsAStandaloneHighResolutionLandscapeArtwork() {
        val artwork = locate("src/main/res/drawable-nodpi/ktv_dashboard_background_iktv50.png")
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
    fun pairedArtistTilesArePackagedAtTheirOriginalLandscapeResolution() {
        listOf(
            "ktv_ref_v50_tile_singers_01.png", "ktv_ref_v50_tile_songs_01.png",
            "ktv_ref_v50_tile_singers_02.png", "ktv_ref_v50_tile_songs_02.png",
            "ktv_ref_v50_tile_singers_03.png", "ktv_ref_v50_tile_songs_03.png",
        ).forEach { name ->
            val artwork = locate("src/main/res/drawable-nodpi/$name")
            assertTrue("split singer artwork $name must be packaged in the app", artwork.isFile)

            val png = artwork.readBytes()
            assertTrue("$name must be a valid PNG", png.size >= 24 &&
                png.take(8).map { it.toInt() and 0xFF } == listOf(137, 80, 78, 71, 13, 10, 26, 10))
            assertTrue("$name must retain the 560px reference width", png.readPngInt(16) == 560)
            assertTrue("$name must retain the 360px reference height", png.readPngInt(20) == 360)
        }
    }

    @Test
    fun addedPhotoPairsAreByteIdenticalToTheExtractedReferenceArtwork() {
        val expected = mapOf(
            "ktv_ref_v50_tile_singers_02.png" to "78e48e864e4ae0a3652a1292d50a3fafe98da19e6937b0be929543ff543fa665",
            "ktv_ref_v50_tile_songs_02.png" to "3679d87f631eb6ba64b25f171600afb31a75ac3d4d36e578eda6475c19761fc1",
            "ktv_ref_v50_tile_singers_03.png" to "b528c1241e05e8a083e0e204b28272d30f0001af6416a0b65381429ea4e98a4a",
            "ktv_ref_v50_tile_songs_03.png" to "528027666af97242dc2d63a7a3224a016e1a8f4b5ce7676e21f60cf14ab96a3d",
        )
        expected.forEach { (name, sha256) ->
            val file = locate("src/main/res/drawable-nodpi/$name")
            assertTrue("photo pair resource $name must be packaged", file.isFile)
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("$name must remain identical to the extracted 50.0.4 art", sha256, actual)
        }
    }

    @Test
    fun runtimeBackdropDrawsTheExactReferenceArtworkWithoutAColorScrim() {
        val implementation = locate("src/main/java/com/homektv/tv/ui/kiosk/KtvDashboardBackground.kt").readText()
        assertTrue("runtime must load the measured IKTV stage artwork", implementation.contains("R.drawable.ktv_dashboard_background_iktv50"))
        assertTrue("artwork must be cropped to the view bounds without distortion", implementation.contains("canvas.drawBitmap"))
        assertTrue("the reference stage must not be darkened by a second overlay", !implementation.contains("scrimPaint"))
        assertTrue(
            "portrait screens must retain a stage-edge crop instead of the empty center strip",
            implementation.contains("PORTRAIT_CROP_MAX_ASPECT"),
        )
        assertTrue("the existing graphite renderer must remain as an image-load fallback", implementation.contains("FallbackGraphiteDrawable"))
    }

    @Test
    fun allTenDashboardTilesUseTheVerifiedReferenceImageFiles() {
        val expected = linkedMapOf(
            "ktv_ref_v50_tile_hot_new.png" to "0987abc5865b421d574465c309b0feb8b5e4210eeb7466023236f52d71124b65",
            "ktv_ref_v50_tile_featured.png" to "32b17969581a54664dc526029f022d95f38fabf511972833a39496851a1d9557",
            "ktv_ref_v50_tile_singers_01.png" to "43024d61fb316e325f5fd3bd7050edf853aed2d1839310db0da901bfc0859d08",
            "ktv_ref_v50_tile_songs_01.png" to "d1e1abd21363d7763f0f09533afa5ca18f957fc03eb4e61bfb68c34e739e7f49",
            "ktv_ref_v50_tile_variety.png" to "f74f419f93b2f55ec2d9b2c3b3af8b46780a0dafa112fbd20fdf8986397e5ac8",
            "ktv_ref_v50_tile_language.png" to "acf563633798b0e62624ba1eda6de7c098156af87b9d8547bfc398538748e333",
            "ktv_ref_v50_tile_topic.png" to "fb31eaff958d9c5f77038dfe3bc28161e15c0696989679cd23f732e176f52c82",
            "ktv_ref_v50_tile_tiktok.png" to "a621bc0a1bf7f03a5746adae1535fc6e38d58afc193e198dcb3ccf2b7aaf0eaf",
            "ktv_ref_v50_tile_favorite.png" to "273d2792c3bbc0366237e26ba57203e806220685144c36bd30a10871616ab300",
            "ktv_ref_v50_tile_local.png" to "ac879b7332c96b0c49a0c06e5a67679ed4eb7a98f82ae04a4bd505e33778d7e3",
        )
        expected.forEach { (name, sha256) ->
            val file = locate("src/main/res/drawable-nodpi/$name")
            assertTrue("reference asset $name must be packaged", file.isFile)
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("$name must remain byte-identical to the verified source", sha256, actual)
        }
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
