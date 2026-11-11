package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvQueueDrawerLayoutContractTest {
    @Test
    fun drawerHasThreeFocusableTabsAndSeparateRealStatePages() {
        val document = parse("src/main/res/layout/dialog_ktv_queue.xml")
        val ids = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as org.w3c.dom.Element }
            .mapNotNull { it.getAttribute("android:id").takeIf(String::isNotBlank) }
            .toSet()

        listOf(
            "drawerTabDownloads",
            "drawerTabQueue",
            "drawerTabHistory",
            "drawerQueuePage",
            "drawerHistoryPage",
            "drawerDownloadsPage",
            "btnDrawerClose",
            "rvHistoryDrawer",
            "txtHistoryEmpty",
            "btnHistoryRetry",
            "btnDownloadStartAll",
            "btnDownloadPauseAll",
            "btnDownloadDeleteAll",
        ).forEach { id -> assertTrue("missing queue drawer view: $id", "@+id/$id" in ids) }

        listOf("drawerTabDownloads", "drawerTabQueue", "drawerTabHistory", "btnDrawerClose")
            .forEach { id ->
                val view = (0 until document.getElementsByTagName("*").length)
                    .map { document.getElementsByTagName("*").item(it) as org.w3c.dom.Element }
                    .single { it.getAttribute("android:id") == "@+id/$id" }
                assertEquals("true", view.getAttribute("android:focusable"))
            }
    }

    @Test
    fun drawerUsesTheReferenceArtworkAndMeasuredTvWidth() {
        val document = parse("src/main/res/layout/dialog_ktv_queue.xml")
        val panel = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("android:id") == "@+id/queueDrawerPanel" }

        assertEquals("@drawable/ktv_ref_v50_queue_drawer_bg", panel.getAttribute("android:background"))
        assertEquals("@dimen/ktv_ref_queue_drawer_width", panel.getAttribute("android:layout_width"))
        assertTrue(locate("src/main/res/drawable-nodpi/ktv_ref_v50_queue_drawer_bg.png").isFile)

        val dimension = parse("src/main/res/values/dimens.xml")
        val drawerWidth = (0 until dimension.getElementsByTagName("dimen").length)
            .map { dimension.getElementsByTagName("dimen").item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == "ktv_ref_queue_drawer_width" }
        assertEquals("388dp", drawerWidth.textContent.trim())
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
