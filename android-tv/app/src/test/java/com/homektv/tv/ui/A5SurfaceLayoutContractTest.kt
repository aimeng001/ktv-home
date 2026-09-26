package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class A5SurfaceLayoutContractTest {
    @Test
    fun setupLayoutExposesResponsiveIntroAndScrollableConnectionForm() {
        val setup = parse("src/main/res/layout/activity_setup.xml")
        val ids = elements(setup).map { it.getAttribute("android:id") }.toSet()

        assertTrue(ids.contains("@+id/setupPage"))
        assertTrue(ids.contains("@+id/setupIntroPanel"))
        assertTrue(ids.contains("@+id/setupHeroPanel"))
        assertTrue(ids.contains("@+id/setupFormScroll"))
        val hero = elements(setup).single { it.getAttribute("android:id") == "@+id/setupHeroPanel" }
        assertEquals("@+id/setupContent", hero.parentNode.attributes.getNamedItem("android:id").nodeValue)
    }

    @Test
    fun standbyRecommendationBandStartsHiddenAndHasNoFakeEmptyMessage() {
        val main = parse("src/main/res/layout/activity_main.xml")
        val byId = elements(main).associateBy { it.getAttribute("android:id") }

        assertEquals("gone", byId.getValue("@+id/recommendationBand").getAttribute("android:visibility"))
        assertFalse(byId.containsKey("@+id/txtRecommendationsEmpty"))
    }

    private fun elements(document: org.w3c.dom.Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

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
