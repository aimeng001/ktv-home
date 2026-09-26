package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvKioskSearchEmptyLayoutContractTest {
    @Test
    fun blankSearchShowsCenteredPromptInResultsArea() {
        val layout = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = (0 until layout.getElementsByTagName("*").length)
            .map { layout.getElementsByTagName("*").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }

        assertTrue(byId.containsKey("@+id/searchEmptyState"))
        assertTrue(byId.containsKey("@+id/imgSearchEmptyIcon"))
        assertTrue(byId.containsKey("@+id/txtSearchEmptyTitle"))
        assertTrue(byId.containsKey("@+id/txtSearchEmptyMessage"))
        assertEquals("vertical", byId.getValue("@+id/searchEmptyState").getAttribute("android:orientation"))
        assertEquals("center", byId.getValue("@+id/searchEmptyState").getAttribute("android:gravity"))
        val icon = byId.getValue("@+id/imgSearchEmptyIcon")
        assertEquals("@color/gold", icon.getAttribute("app:tint"))
        assertEquals("@string/search_icon_accessibility", icon.getAttribute("android:contentDescription"))
        assertEquals("@string/search_empty_title", byId.getValue("@+id/txtSearchEmptyTitle").getAttribute("android:text"))
        assertEquals("@string/search_empty_message", byId.getValue("@+id/txtSearchEmptyMessage").getAttribute("android:text"))
    }

    @Test
    fun promptIsOnlyVisibleForAnEmptyOrUnmatchedQueryAndDoesNotLoadHotSongs() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("overlay.searchEmptyState.visibility ="))
        assertTrue(source.contains("overlay.txtSearchCount.visibility ="))
        assertTrue(source.contains("overlay.searchEmptyState"))
        assertTrue(source.contains("private fun renderSearchEmptyState()"))
        assertTrue(source.substringAfter("private fun applyTabSelection").contains("renderSearchEmptyState()"))
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
