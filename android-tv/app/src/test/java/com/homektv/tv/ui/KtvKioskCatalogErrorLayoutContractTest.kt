package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvKioskCatalogErrorLayoutContractTest {
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

    @Test
    fun threeColumnSingerRootShowsRetryInsteadOfAFalseOnePageIndicator() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val renderMethod = source.substringAfter("private fun renderThreeColumnBrowser")
            .substringBefore("private fun isThreeColumnTab")

        assertTrue(renderMethod.contains("val regionFilterUnconfirmed = state.catalogStatus.code == \"ARTIST_REGION_FILTER_UNCONFIRMED\""))
        assertTrue(renderMethod.contains("!hasSingerRows && !state.catalogLoading &&"))
        assertTrue(renderMethod.contains("(catalogError != null || regionFilterUnconfirmed)"))
        assertTrue(renderMethod.contains("catalogError != null -> \"歌手列表加载失败，按确定重试\""))
        assertTrue(renderMethod.contains("browser.txtBrowserEmptyState.setOnClickListener"))
        assertTrue(renderMethod.contains("browser.txtBrowserEmptyState.isFocusable = retrySingerList"))
        assertTrue(renderMethod.contains("browser.txtPageNumber.visibility = if (hasSingerRows) View.VISIBLE else View.GONE"))

        val retryMethod = source.substringAfter("private fun showBrowserRetry(")
            .substringBefore("private fun renderSingerInitialRail(")
        assertTrue(
            "showBrowserRetry must support non-overlapping filter rail retry when songs are already visible",
            retryMethod.contains("hasVisibleResults: Boolean") && retryMethod.contains("shouldShowKeyboard && !hasVisibleResults"),
        )
        val keyMethod = source.substringAfter("fun dispatchKeyEvent(event: KeyEvent): Boolean")
        assertTrue(
            "remote digit dispatch must check visible keyboard and non-EditText focus",
            keyMethod.contains("isSearchKeyboardShowing()") && keyMethod.contains("currentFocus !is android.widget.EditText"),
        )
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
