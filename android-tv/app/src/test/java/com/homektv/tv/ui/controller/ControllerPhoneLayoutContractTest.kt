package com.homektv.tv.ui.controller

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ControllerPhoneLayoutContractTest {
    @Test
    fun phoneBottomBarContainsMiniPlaybackAndTheFiveTabNavigation() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/fragment_controller.xml"))
        val byId = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) }
            .filterIsInstance<Element>()
            .associateBy { it.getAttribute("android:id") }

        assertEquals("vertical", byId.getValue("@+id/phoneBottomBar").getAttribute("android:orientation"))
        assertEquals("@+id/phoneBottomBar", byId.getValue("@+id/miniPlayerRow").parentNode.attributes
            .getNamedItem("android:id")?.nodeValue)
        assertEquals("@+id/phoneBottomBar", byId.getValue("@+id/phoneNav").parentNode.attributes
            .getNamedItem("android:id")?.nodeValue)
        assertTrue(byId.containsKey("@+id/miniPlayerTitle"))
        assertTrue(byId.containsKey("@+id/miniPlayerToggle"))
        assertTrue(byId.containsKey("@+id/miniPlayerQueue"))
    }

    @Test
    fun fragmentStillSavesSearchPanelAndDirectoryRestorationState() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("outState.putString(KEY_QUERY"))
        assertTrue(source.contains("outState.putString(KEY_PHONE_PANEL"))
        assertTrue(source.contains("KEY_CATALOG_PAGE"))
        assertTrue(source.contains("KEY_SCROLL_Y"))
        assertTrue(source.contains("restorePanelData()"))
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
