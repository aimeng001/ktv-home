package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvPitchDialogLayoutContractTest {
    @Test
    fun pitchScaleExposesTheFullTenStepRangeAndReferenceDockActions() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/dialog_ktv_pitch.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val seek = byId.getValue("@+id/seekPitch")

        assertEquals("10", seek.getAttribute("android:max"))
        assertEquals("5", seek.getAttribute("android:progress"))
        assertEquals("@id/btnPitchDown", seek.getAttribute("android:nextFocusLeft"))
        assertEquals("@id/btnPitchUp", seek.getAttribute("android:nextFocusRight"))
        assertEquals("#31c27c", seek.getAttribute("android:progressTint"))
        assertEquals("@drawable/ktv_ref_v50_dock_tone_down", byId.getValue("@+id/btnPitchDown").getAttribute("android:src"))
        assertEquals("@drawable/ktv_ref_v50_dock_tone_up", byId.getValue("@+id/btnPitchUp").getAttribute("android:src"))
        listOf("btnPitchDown", "seekPitch", "btnPitchUp", "btnPitchReset", "btnPitchClose").forEach { id ->
            assertEquals("true", byId.getValue("@+id/$id").getAttribute("android:focusable"))
        }
    }

    @Test
    fun pitchDialogLayoutDisplaysNumericScaleTicks() {
        val xml = locate("src/main/res/layout/dialog_ktv_pitch.xml").readText()
        listOf("-5", "-4", "-3", "-2", "-1", "0", "+1", "+2", "+3", "+4", "+5").forEach { tick ->
            assertTrue("dialog_ktv_pitch 必须包含刻度标记 $tick", xml.contains("android:text=\"$tick\""))
        }
    }

    @Test
    fun pitchDialogSupportsUnavailableModeDisablingControlsAndFocusingClose() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvPitchDialog.kt").readText()
        assertTrue(source.contains("unavailableStatus: PlaybackPitchStatus? = null"))
        assertTrue(source.contains("btnPitchClose.requestRemoteFocus()"))
        assertTrue(source.contains("isEnabled = false"))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequence {
            yield(workingDirectory.resolve(relativePath))
            yield(workingDirectory.resolve("app/$relativePath"))
            yield(workingDirectory.resolve("../$relativePath"))
            yield(workingDirectory.resolve("../../$relativePath"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
