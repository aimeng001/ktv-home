package com.homektv.tv.ui.controller

import com.homektv.tv.controller.ControllerConnection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ControllerPhoneRemoteLayoutContractTest {
    @Test
    fun remotePageHasCurrentSongSummaryBeforeControlsAndKeepsEveryExistingControl() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/fragment_controller.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) }
            .filterIsInstance<Element>()
        val byId = elements.associateBy { it.getAttribute("android:id") }
        val card = byId.getValue("@+id/remoteNowPlayingCard")
        val title = byId.getValue("@+id/remoteNowPlayingTitle")
        val state = byId.getValue("@+id/remoteNowPlayingState")
        val controls = byId.getValue("@+id/remoteControlsContainer")

        assertEquals("@+id/remoteNowPlayingCard", title.parentNode.attributes.getNamedItem("android:id").nodeValue)
        assertEquals("@+id/remoteNowPlayingCard", state.parentNode.attributes.getNamedItem("android:id").nodeValue)
        assertEquals("@string/controller_remote_no_song", state.getAttribute("android:text"))
        assertTrue(elements.indexOf(card) < elements.indexOf(controls))
        listOf("@+id/remoteControlsContainer", "@+id/volumeSeek", "@+id/seekBar")
            .forEach { assertTrue(byId.containsKey(it)) }
    }

    @Test
    fun remoteSummaryUsesOnlyTheExistingQueueSnapshotAndDoesNotChangeControlActions() {
        val source = locate("src/main/java/com/homektv/tv/ui/controller/ControllerFragment.kt").readText()

        assertTrue(source.contains("binding.remoteNowPlayingTitle.text"))
        assertTrue(source.contains("val now = state.queue.playing"))
        assertTrue(source.contains("val currentSong = now?.song"))
        assertTrue(source.contains("binding.remoteNowPlayingState.text"))
        assertTrue(source.contains("ControllerRemoteStatusPolicy.label"))
        assertTrue(source.contains("binding.remoteControlsContainer"))
        assertTrue(source.contains("viewModel.control(\"pause\")") || source.contains("viewModel.control(action)"))
    }

    @Test
    fun remoteStatusNeverLabelsUnknownOrDisconnectedPlaybackAsPaused() {
        assertEquals(
            "服务离线",
            ControllerRemoteStatusPolicy.label(ControllerConnection.OFFLINE, tvOnline = true, hasSong = true, playbackState = "playing"),
        )
        assertEquals(
            "电视未连接",
            ControllerRemoteStatusPolicy.label(ControllerConnection.ONLINE, tvOnline = false, hasSong = true, playbackState = "playing"),
        )
        assertEquals(
            "暂无歌曲",
            ControllerRemoteStatusPolicy.label(ControllerConnection.ONLINE, tvOnline = true, hasSong = false, playbackState = "paused"),
        )
        assertEquals(
            "正在播放",
            ControllerRemoteStatusPolicy.label(ControllerConnection.ONLINE, tvOnline = true, hasSong = true, playbackState = "playing"),
        )
        assertEquals(
            "已暂停",
            ControllerRemoteStatusPolicy.label(ControllerConnection.ONLINE, tvOnline = true, hasSong = true, playbackState = "paused"),
        )
        assertEquals(
            "播放状态未知",
            ControllerRemoteStatusPolicy.label(ControllerConnection.ONLINE, tvOnline = true, hasSong = true, playbackState = "new-state"),
        )
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
