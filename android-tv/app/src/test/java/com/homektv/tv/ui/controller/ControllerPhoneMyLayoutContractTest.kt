package com.homektv.tv.ui.controller

import com.homektv.tv.controller.ControllerConnection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ControllerPhoneMyLayoutContractTest {
    @Test
    fun myPanelContainsASecretFreeConnectionCardAndExistingPersonalControls() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/fragment_controller.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) }
            .filterIsInstance<Element>()
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val card = byId.getValue("@+id/myConnectionCard")

        listOf("myConnectionTitle", "myConnectionStatus", "btnMyServerSetup").forEach {
            val child = byId.getValue("@+id/$it")
            assertEquals("@+id/myConnectionCard", child.parentNode.attributes.getNamedItem("android:id").nodeValue)
        }
        assertEquals("gone", card.getAttribute("android:visibility"))
        assertTrue(byId.containsKey("@+id/personalTabsContainer"))
        assertTrue(byId.containsKey("@+id/personalList"))
        val cardMarkup = card.textContent.orEmpty().lowercase()
        assertFalse(cardMarkup.contains("token"))
        assertFalse(cardMarkup.contains("credential"))
    }

    @Test
    fun phoneConnectionCardUsesTheSameStatusLabelAndTheExistingSetupRoute() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("ControllerConnectionSummaryPolicy.label"))
        assertTrue(source.contains("binding.myConnectionStatus.text"))
        assertTrue(source.contains("binding.btnMyServerSetup.setOnClickListener"))
        assertTrue(source.contains("openServerSetup()"))
    }

    @Test
    fun connectionStatusDoesNotConfuseServerAvailabilityWithTvAvailability() {
        assertEquals("连接中…", ControllerConnectionSummaryPolicy.label(ControllerConnection.CONNECTING, tvOnline = false))
        assertEquals("电视在线", ControllerConnectionSummaryPolicy.label(ControllerConnection.ONLINE, tvOnline = true))
        assertEquals("服务在线 · 电视未连接", ControllerConnectionSummaryPolicy.label(ControllerConnection.ONLINE, tvOnline = false))
        assertEquals("服务离线", ControllerConnectionSummaryPolicy.label(ControllerConnection.OFFLINE, tvOnline = false))
    }

    @Test
    fun playlistHeaderRenderKeySkipsUnchangedRebuildsAndDetectsAsyncCoverBytes() {
        val detail = com.homektv.tv.net.PlaylistDetail(
            id = 7L,
            name = "怀旧金曲",
            description = "经典合集",
            theme = "经典",
            coverUrl = "/api/cover/7",
            songs = emptyList(),
        )
        val withoutBytes = PlaylistHeaderRenderKey.from(detail, null)
        val withBytesA = PlaylistHeaderRenderKey.from(detail, byteArrayOf(1, 2, 3))
        val withBytesASameContent = PlaylistHeaderRenderKey.from(detail, byteArrayOf(1, 2, 3))
        val withBytesB = PlaylistHeaderRenderKey.from(detail, byteArrayOf(1, 2, 4))

        assertEquals(withBytesA, withBytesASameContent)
        assertFalse(withoutBytes == withBytesA)
        assertFalse(withBytesA == withBytesB)

        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()
        assertTrue(source.contains("renderedPlaylistHeaderKey"))
        assertTrue(source.contains("PlaylistHeaderRenderKey.from("))
        val personalPanel = source.substringAfter("private fun renderPersonalPanel(")
            .substringBefore("private fun renderPlaylistDetailHeader(")
        assertTrue(personalPanel.contains("clearPlaylistHeader()"))
        assertTrue(source.contains("playlistCoverJob?.cancel()"))
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
