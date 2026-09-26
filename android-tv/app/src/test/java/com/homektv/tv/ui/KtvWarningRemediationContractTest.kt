package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvWarningRemediationContractTest {
    @Test
    fun queueNowPlayingCardUsesDarkGoldSurfaceInsteadOfBrightLegacyPink() {
        val queueLayout = parse("src/main/res/layout/dialog_ktv_queue.xml")
        val section = elements(queueLayout).single { it.getAttribute("android:id") == "@+id/nowPlayingSection" }

        assertEquals("@drawable/bg_queue_now_playing", section.getAttribute("android:background"))
        assertTrue(!section.getAttribute("android:background").contains("karaoke_label"))
    }

    @Test
    fun kioskOverlayIsInflatedOnDemandInsteadOfAsPartOfMainLayout() {
        val mainLayout = parse("src/main/res/layout/activity_main.xml")
        val overlaySlot = elements(mainLayout).singleOrNull {
            it.getAttribute("android:id") == "@+id/kioskOverlayStub"
        }
        assertEquals("ViewStub", overlaySlot?.tagName)
        assertEquals("@layout/view_ktv_kiosk_overlay", overlaySlot?.getAttribute("android:layout"))

        val mainActivity = read("src/main/java/com/homektv/tv/ui/MainActivity.kt")
        assertTrue(mainActivity.contains("private fun ensureKioskController()"))
        assertTrue(mainActivity.contains("kioskOverlayStub.inflate()"))
        assertTrue(mainActivity.contains("cachedQueueSnapshot?.let(kioskController::updateSnapshot)"))
    }

    @Test
    fun modalDialogsUseWindowDimmingInsteadOfOpaqueRootScrims() {
        val qrLayout = parse("src/main/res/layout/dialog_ktv_qr.xml")
        val queueLayout = parse("src/main/res/layout/dialog_ktv_queue.xml")
        assertEquals("@android:color/transparent", elements(qrLayout).first().getAttribute("android:background"))
        assertEquals("@android:color/transparent", elements(queueLayout).first().getAttribute("android:background"))

        val qrDialog = read("src/main/java/com/homektv/tv/ui/KtvQrDialog.kt")
        val queueDialog = read("src/main/java/com/homektv/tv/ui/KtvQueueDrawerDialog.kt")
        assertTrue(qrDialog.contains("FLAG_DIM_BEHIND"))
        assertTrue(qrDialog.contains("setDimAmount(170f / 255f)"))
        assertTrue(queueDialog.contains("FLAG_DIM_BEHIND"))
        assertTrue(queueDialog.contains("setDimAmount(136f / 255f)"))
    }

    private fun elements(document: org.w3c.dom.Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun read(relativePath: String) = locate(relativePath).readText()

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("android-tv/app/$relativePath"),
            workingDirectory.resolve("../app/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
