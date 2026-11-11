package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KioskPipLayoutContractTest {
    @Test
    fun rootDockOwnsPipPlayerAndMainLayoutHasThreeBands() {
        val mainLayout = locate("src/main/res/layout/activity_main.xml")
        val mainDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mainLayout)
        val mainElements = mainDocument.getElementsByTagName("*")
        fun mainById(id: String): Element =
            (0 until mainElements.length)
                .map { mainElements.item(it) }
                .filterIsInstance<Element>()
                .firstOrNull { it.getAttribute("android:id") == "@+id/$id" }
                ?: error("Missing view $id")

        assertEquals("48dp", mainById("header_bar").getAttribute("android:layout_height"))
        assertEquals("64dp", mainById("page_container").getAttribute("android:layout_marginTop"))
        assertEquals("match_parent", mainById("page_container").getAttribute("android:layout_height"))
        assertEquals(mainById("page_container"), mainById("kioskOverlayStub").parentNode)
        assertEquals("gone", mainById("homeHeroPanel").getAttribute("android:visibility"))
        val dockInclude = (0 until mainElements.length)
            .map { mainElements.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.getAttribute("layout") == "@layout/view_ktv_bottom_dock" }
            ?: error("Missing global bottom dock include")
        assertEquals("@dimen/ktv_dashboard_bottom_dock_height", dockInclude.getAttribute("android:layout_height"))

        val dockLayout = locate("src/main/res/layout/view_ktv_bottom_dock.xml")
        val dockDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dockLayout)
        val dockElements = dockDocument.getElementsByTagName("*")
        fun dockById(id: String): Element =
            (0 until dockElements.length)
                .map { dockElements.item(it) }
                .filterIsInstance<Element>()
                .firstOrNull { it.getAttribute("android:id") == "@+id/$id" }
                ?: error("Missing dock view $id")

        val video = dockById("video_container")
        assertEquals("@dimen/ktv_dashboard_pip_width", video.getAttribute("android:layout_width"))
        assertEquals("@dimen/ktv_dashboard_pip_height", video.getAttribute("android:layout_height"))
        assertEquals("androidx.media3.ui.PlayerView", mainById("playerView").tagName)
        assertEquals("@+id/sharedPlayerContainer", mainById("playerView").parentNode.let { (it as Element).getAttribute("android:id") })

        val kioskLayout = locate("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val kioskDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(kioskLayout)
        val kioskElements = kioskDocument.getElementsByTagName("*")
        val legacyPip = (0 until kioskElements.length)
            .map { kioskElements.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.getAttribute("android:id") == "@+id/pipVideoFrame" }
            ?: error("Missing legacy PiP placeholder")
        assertEquals("gone", legacyPip.getAttribute("android:visibility"))
        assertNotNull(legacyPip)
    }

    @Test
    fun kioskOverlayDoesNotContainDuplicateHeaderBar() {
        val kioskLayout = locate("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(kioskLayout)
        val elements = doc.getElementsByTagName("*")
        val headers = (0 until elements.length).map { elements.item(it) }
            .filterIsInstance<Element>()
            .filter { it.getAttribute("android:layout_height") == "52dp" }
        assertTrue("view_ktv_kiosk_overlay 绝不允许包含多余的 52dp 顶部栏，保持 1080P 满额视口", headers.isEmpty())
    }

    @Test
    fun pipRespondsToMeasuredStageAndLocalPlayerBufferEvents() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("Player.STATE_BUFFERING && it.playWhenReady"))
        assertTrue(source.contains("bottomDockController.setVideoVisible(state.videoVisible && !externalDisplayActive)"))
        assertTrue(source.contains("binding.kioskOverlay.pipVideoFrame.visibility = View.GONE"))
        assertTrue(source.contains("bottomDockController.ensurePlayerInDock()"))
    }

    private fun locate(relativePath: String): File = listOf(
        File(relativePath),
        File("app/$relativePath"),
        File("android-tv/app/$relativePath"),
        File("../$relativePath"),
        File("../../android-tv/app/$relativePath"),
    ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
}
