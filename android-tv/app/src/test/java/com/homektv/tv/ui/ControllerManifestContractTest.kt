package com.homektv.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ControllerManifestContractTest {

    @Test
    fun manifestMustRegisterControllerActivity() {
        // Path relative to project root or app module
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("android-tv/app/src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: File("src/main/AndroidManifest.xml")

        assertTrue("AndroidManifest.xml 文件必须存在", manifestFile.isFile)
        val content = manifestFile.readText()
        assertTrue(
            "AndroidManifest.xml 必须声明 ControllerActivity",
            content.contains("android:name=\".ui.ControllerActivity\""),
        )
        assertTrue(
            "ControllerActivity 必须声明为 exported=false",
            content.contains("android:exported=\"false\""),
        )
    }
}
