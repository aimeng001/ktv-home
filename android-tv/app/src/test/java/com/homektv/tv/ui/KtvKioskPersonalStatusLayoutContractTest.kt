package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvKioskPersonalStatusLayoutContractTest {
    @Test
    fun personalEmptyAndErrorStateUsesCenteredCardWithOptionalRetry() {
        val layout = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = (0 until layout.getElementsByTagName("*").length)
            .map { layout.getElementsByTagName("*").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }

        assertTrue(byId.containsKey("@+id/personalStatusPanel"))
        assertTrue(byId.containsKey("@+id/txtPersonalStatusTitle"))
        assertTrue(byId.containsKey("@+id/txtPersonalStatusMessage"))
        assertTrue(byId.containsKey("@+id/btnPersonalRetry"))
        assertEquals("@string/personal_favorites_empty_title", byId.getValue("@+id/txtPersonalStatusTitle").getAttribute("android:text"))
        assertEquals("@string/personal_favorites_empty_message", byId.getValue("@+id/txtPersonalStatusMessage").getAttribute("android:text"))
        assertEquals("@string/common_retry", byId.getValue("@+id/btnPersonalRetry").getAttribute("android:text"))
        assertEquals("center", byId.getValue("@+id/personalStatusPanel").getAttribute("android:gravity"))
        assertEquals("@drawable/bg_singer_card", byId.getValue("@+id/personalStatusPanel").getAttribute("android:background"))
    }

    @Test
    fun personalErrorStatusRetriesOnlyReadOnlyFavoritesOrHistoryLoad() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("private fun renderPersonalStatus"))
        assertTrue(source.contains("overlay.btnPersonalRetry.setOnClickListener"))
        assertTrue(source.contains("personalActionRouter.favorites()"))
        assertTrue(source.contains("personalActionRouter.history()"))
        assertTrue(source.contains("personalStatusPanel.visibility ="))
    }

    @Test
    fun failedFavoritesAndHistoryReadsDoNotMasqueradeAsSuccessfulZeroCounts() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("val favoritesError = state.errorFor(com.homektv.tv.controller.UiDomain.FAVORITES)"))
        assertTrue(source.contains("favoritesError != null) {"))
        assertTrue(source.contains("\"我的收藏暂不可用\""))
        assertTrue(source.contains("val visibleHistory = state.history.mapNotNull { it.song }.distinctBy { it.id }"))
        assertTrue(source.contains("val historyError = state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY)"))
        assertTrue(source.contains("historyError != null) {"))
        assertTrue(source.contains("\"最近唱过暂不可用\""))
        assertTrue(source.contains("personalSongAdapter.submitList(visibleHistory)"))
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
