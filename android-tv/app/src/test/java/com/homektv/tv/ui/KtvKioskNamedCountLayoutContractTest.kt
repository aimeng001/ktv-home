package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvKioskNamedCountLayoutContractTest {
    @Test
    fun namedCountRowsUseFocusableCardWithSeparateNameAndCount() {
        val layoutFile = locateOrNull("src/main/res/layout/item_kiosk_named_count.xml")
        assertNotNull("language/tag catalog needs its own card layout", layoutFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)
        val root = document.documentElement

        assertEquals("LinearLayout", root.tagName)
        assertEquals("wrap_content", root.getAttribute("android:layout_height"))
        assertEquals("horizontal", root.getAttribute("android:orientation"))
        assertEquals("true", root.getAttribute("android:focusable"))
        assertEquals("true", root.getAttribute("android:clickable"))
        assertEquals("@drawable/bg_singer_card", root.getAttribute("android:background"))

        val children = document.getElementsByTagName("ImageView")
        assertEquals(1, children.length)
        assertEquals("@color/gold", (children.item(0) as Element).getAttribute("app:tint"))
        val textIds = (0 until document.getElementsByTagName("TextView").length)
            .map { (document.getElementsByTagName("TextView").item(it) as Element).getAttribute("android:id") }
        assertTrue(textIds.contains("@+id/txtNamedCountName"))
        assertTrue(textIds.contains("@+id/txtNamedCountSongs"))
    }

    @Test
    fun namedCountAdapterInflatesCardRatherThanPlatformDefaultButton() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvKioskNamedCountAdapter.kt").readText()

        assertTrue(source.contains("ItemKioskNamedCountBinding.inflate"))
        assertTrue(source.contains("named_count_selection_description"))
        assertTrue(!source.contains("Button(parent.context)"))
    }

    private fun locateOrNull(relativePath: String): File? {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile)
    }

    private fun locate(relativePath: String): File =
        locateOrNull(relativePath) ?: error("Cannot locate $relativePath")
}
