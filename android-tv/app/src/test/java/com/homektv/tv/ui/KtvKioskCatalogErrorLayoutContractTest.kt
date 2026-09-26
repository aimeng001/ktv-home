package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvKioskCatalogErrorLayoutContractTest {
    @Test
    fun categoryFailureUsesCenteredFocusableRetryCard() {
        val layout = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = (0 until layout.getElementsByTagName("*").length)
            .map { layout.getElementsByTagName("*").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }

        assertTrue(byId.containsKey("@+id/categoryErrorPanel"))
        assertTrue(byId.containsKey("@+id/txtCategoryErrorDetail"))
        assertTrue(byId.containsKey("@+id/btnCategoryRetry"))
        assertEquals("@string/catalog_error_message", byId.getValue("@+id/txtCategoryErrorDetail").getAttribute("android:text"))
        assertEquals("@string/common_retry", byId.getValue("@+id/btnCategoryRetry").getAttribute("android:text"))
        assertTrue(locate("src/main/res/layout/view_ktv_kiosk_overlay.xml").readText().contains("@string/catalog_error_title"))
        assertTrue(locate("src/main/res/layout/view_ktv_kiosk_overlay.xml").readText().contains("@string/catalog_error_icon_accessibility"))
        assertEquals("true", byId.getValue("@+id/btnCategoryRetry").getAttribute("android:focusable"))
        assertEquals("@drawable/bg_singer_card", byId.getValue("@+id/categoryErrorPanel").getAttribute("android:background"))
    }

    @Test
    fun categoryErrorShowsRetryCardAndRetriesCurrentRealDirectory() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()

        assertTrue(source.contains("overlay.btnCategoryRetry.setOnClickListener"))
        assertTrue(source.contains("overlay.categoryErrorPanel.visibility ="))
        assertTrue(source.contains("overlay.categoryRecyclerView.visibility ="))
        assertTrue(source.contains("catalogError != null"))
        assertTrue(source.contains("catalogActionRouter.languages()"))
        assertTrue(source.contains("catalogActionRouter.tags()"))
    }

    @Test
    fun singerDirectoryFailureHasAnExplicitFocusableRetryAction() {
        val layout = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")
        val elements = (0 until layout.getElementsByTagName("*").length)
            .map { layout.getElementsByTagName("*").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val retry = byId["@+id/btnSingerRetry"]

        assertTrue("singer retry button must exist", retry != null)
        assertEquals("@string/common_retry", retry?.getAttribute("android:text"))
        assertEquals("true", retry?.getAttribute("android:focusable"))

        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(source.contains("overlay.btnSingerRetry.setOnClickListener"))
        assertTrue(source.contains("overlay.btnSingerRetry.visibility ="))
        assertTrue(source.contains("gender = controllerState.artistGender"))
        assertTrue(source.contains("initial = controllerState.artistInitial"))
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
