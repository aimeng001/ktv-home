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
    fun playingVideoIsAnchoredAtLowerCenterWithoutCoveringSelectionContent() {
        val layout = listOf(
            File("src/main/res/layout/view_ktv_kiosk_overlay.xml"),
            File("android-tv/app/src/main/res/layout/view_ktv_kiosk_overlay.xml"),
            File("../app/src/main/res/layout/view_ktv_kiosk_overlay.xml"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate kiosk layout")

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val elements = document.getElementsByTagName("*")
        fun byId(id: String): Element =
            (0 until elements.length)
                .map { elements.item(it) }
                .filterIsInstance<Element>()
                .firstOrNull { it.getAttribute("android:id") == "@+id/$id" }
                ?: error("Missing view $id")

        val content = byId("kioskMainContent")
        val pip = byId("pipVideoFrame")
        val anchor = byId("pipVideoAnchor")

        assertEquals("androidx.constraintlayout.widget.ConstraintLayout", pip.parentNode.nodeName)
        assertEquals(pip.parentNode, content.parentNode)
        assertEquals("parent", pip.getAttribute("app:layout_constraintStart_toStartOf"))
        assertEquals("parent", pip.getAttribute("app:layout_constraintEnd_toEndOf"))
        assertEquals("parent", pip.getAttribute("app:layout_constraintBottom_toBottomOf"))
        assertEquals("16:9", pip.getAttribute("app:layout_constraintDimensionRatio"))
        assertEquals("", pip.getAttribute("app:layout_constraintWidth_min"))
        assertEquals("false", pip.getAttribute("android:focusable"))
        assertEquals("@id/pipVideoFrame", content.getAttribute("app:layout_constraintBottom_toTopOf"))
        assertEquals("gone", pip.getAttribute("android:visibility"))
        assertEquals("gone", byId("btnKioskExit").getAttribute("android:visibility"))
        assertEquals("true", byId("btnKioskExit").getAttribute("android:focusable"))
        assertEquals("blocksDescendants", anchor.getAttribute("android:descendantFocusability"))
        assertEquals(pip, anchor.parentNode)
        assertNotNull(anchor)
    }

    @Test
    fun pipRespondsToMeasuredStageAndLocalPlayerBufferEvents() {
        val source = listOf(
            File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Cannot locate kiosk controller")

        assertTrue(source.contains("kioskContentStage.addOnLayoutChangeListener(pipLayoutChangeListener)"))
        assertTrue(source.contains("KioskPipResponsivePolicy.resolve("))
        assertTrue(source.contains("Player.STATE_BUFFERING && it.playWhenReady"))
        assertTrue(source.contains("if (state.videoVisible && pipSpaceAvailable) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("binding.kioskOverlay.btnKioskExit.visibility ="))
        assertTrue(source.contains("if (!state.videoVisible && binding.kioskOverlay.btnKioskExit.hasFocus())"))
        assertTrue(source.contains("tabHistory.nextFocusRightId"))
    }
}
