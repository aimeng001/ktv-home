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
}
