package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TvOnlyManifestContractTest {

    @Test
    fun manifestExcludesControllerActivityAndLocksLandscapeForTvActivities() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("android-tv/app/src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: File("src/main/AndroidManifest.xml")

        assertTrue("AndroidManifest.xml 文件必须存在", manifestFile.isFile)
        val content = manifestFile.readText()
        assertFalse(
            "AndroidManifest.xml 不得再注册 ControllerActivity",
            content.contains("ControllerActivity"),
        )

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val activities = (0 until document.getElementsByTagName("activity").length)
            .map { document.getElementsByTagName("activity").item(it) }
            .filterIsInstance<Element>()
            .associateBy { it.getAttribute("android:name") }

        assertEquals(setOf(".ui.MainActivity", ".ui.SetupActivity"), activities.keys)
        assertEquals(
            "sensorLandscape",
            activities.getValue(".ui.MainActivity").getAttribute("android:screenOrientation"),
        )
        assertEquals(
            "sensorLandscape",
            activities.getValue(".ui.SetupActivity").getAttribute("android:screenOrientation"),
        )
    }

    @Test
    fun manifestProvidesAdaptiveAndRasterLauncherIconsAndDirectTvBanner() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("android-tv/app/src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: File("src/main/AndroidManifest.xml")

        val resDir = File(manifestFile.parentFile, "res")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val application = document.getElementsByTagName("application").item(0) as Element

        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:icon"))
        assertEquals("@mipmap/ic_launcher_round", application.getAttribute("android:roundIcon"))
        assertEquals("@drawable/tv_banner", application.getAttribute("android:banner"))

        assertFalse(
            "mipmap-anydpi without -v26 overrides density PNGs as -v21 and hides raster fallback icons",
            File(resDir, "mipmap-anydpi/ic_launcher.xml").exists(),
        )
        assertTrue(File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").isFile)
        assertTrue(File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml").isFile)

        listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { density ->
            assertTrue("Missing mipmap-$density/ic_launcher.png", File(resDir, "mipmap-$density/ic_launcher.png").isFile)
            assertTrue("Missing mipmap-$density/ic_launcher_round.png", File(resDir, "mipmap-$density/ic_launcher_round.png").isFile)
        }
        assertTrue(File(resDir, "drawable-xhdpi/tv_banner.png").isFile)
    }
}
