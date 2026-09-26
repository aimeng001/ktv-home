package com.homektv.tv.ui.controller

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ControllerPhoneCatalogLayoutContractTest {
    @Test
    fun phoneDirectoryExposesOnlyTheThreeSupportedCatalogEntrances() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/fragment_controller.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) }
            .filterIsInstance<Element>()
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val entries = listOf(
            "btnPhoneCatalogArtists" to ("controller_catalog_singers" to "歌手"),
            "btnPhoneCatalogLanguages" to ("controller_catalog_languages" to "语种"),
            "btnPhoneCatalogCategories" to ("controller_catalog_categories" to "分类"),
        )
        val stringsDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/values/strings.xml"))
        val stringValues = (0 until stringsDocument.getElementsByTagName("string").length)
            .map { stringsDocument.getElementsByTagName("string").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }

        val container = byId.getValue("@+id/catalogPhoneEntries")
        entries.forEach { (id, resource) ->
            val button = byId.getValue("@+id/$id")
            assertEquals("@string/${resource.first}", button.getAttribute("android:text"))
            assertEquals(resource.second, stringValues[resource.first])
            assertEquals("@+id/catalogPhoneEntries", button.parentNode.attributes.getNamedItem("android:id").nodeValue)
        }
        assertTrue(container.getAttribute("android:orientation") in setOf("vertical", "horizontal"))
        assertTrue(elements.none { element ->
            element.getAttribute("android:id").startsWith("@+id/btnPhoneCatalog") &&
                element.getAttribute("android:text") !in entries.map { "@string/${it.second.first}" }
        })
    }

    @Test
    fun phoneCatalogEntriesCallTheExistingArtistLanguageAndTagLoaders() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("btnPhoneCatalogArtists.setOnClickListener"))
        assertTrue(source.contains("btnPhoneCatalogLanguages.setOnClickListener"))
        assertTrue(source.contains("btnPhoneCatalogCategories.setOnClickListener"))
        assertTrue(source.contains("viewModel.loadArtists()"))
        assertTrue(source.contains("viewModel.loadLanguages()"))
        assertTrue(source.contains("viewModel.loadTags()"))
    }

    @Test
    fun phoneCatalogErrorsExposeRetryForTheCurrentDirectoryButEmptyAndScanningStatesDoNot() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("catalogRetryAvailable"))
        assertTrue(source.contains("ActionPanelRow("))
        assertTrue(source.contains("重试当前目录"))
        assertTrue(source.contains("retryCurrentCatalog(state)"))
        assertTrue(source.contains("CatalogLoadState.SCANNING"))
        assertTrue(source.contains("CatalogLoadState.FILTER_EMPTY"))
    }

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
