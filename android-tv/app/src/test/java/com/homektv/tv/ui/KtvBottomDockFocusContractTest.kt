package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class KtvBottomDockFocusContractTest {
    @Test
    fun visibleDockControlsUseTheReferenceBottomAnchoredBaseline() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml"))
        val byId = (0 until document.getElementsByTagName("TextView").length)
            .map { document.getElementsByTagName("TextView").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }
        listOf(
            "btnDockPlayPause", "btnDockVocal", "btnDockRestart", "btnDockNext", "btnDockVolDown",
            "btnDockVolUp", "btnDockToneDown", "btnDockToneUp", "btnDockDownloads", "btnDockQueue",
        ).forEach { id ->
            val control = byId.getValue("@+id/$id")
            assertEquals("30dp", control.getAttribute("android:paddingTop"))
            assertEquals("8dp", control.getAttribute("android:paddingBottom"))
        }
    }

    @Test
    fun bottomDockControlsDeclareNextFocusUp() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml"))
        val elements = (0 until document.getElementsByTagName("*").length)
            .map { document.getElementsByTagName("*").item(it) as Element }
            .filter { it.getAttribute("android:focusable") == "true" }

        assertTrue("底栏必须包含可聚焦控件", elements.isNotEmpty())
        elements.forEach { element ->
            val id = element.getAttribute("android:id")
            val nextUp = element.getAttribute("android:nextFocusUp")
            assertTrue("$id 必须声明 android:nextFocusUp 以保证基础焦点连通", nextUp.startsWith("@id/"))
        }
    }

    @Test
    fun bottomDockControllerSupportsDynamicFocusUpSwitching() {
        val source = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        assertTrue("Controller 必须支持根据大厅/点歌台动态更新焦点向上目标", source.contains("fun updateFocusUpTargets("))
        assertTrue(source.contains("btnDockPlayPause.nextFocusUpId = browserLeftTarget"))
        assertTrue(source.contains("btnDockVolDown.nextFocusUpId = browserCenterTarget"))
        assertTrue(source.contains("btnDockToneDown.nextFocusUpId = if (isDashboard) R.id.tileFavorites"))
        assertTrue(source.contains("btnDockDownloads.nextFocusUpId = browserRightTarget"))
    }

    @Test
    fun referencePitchAndDownloadControlsRemainFocusableAndUseTheRealActionRoutes() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(locate("src/main/res/layout/view_ktv_bottom_dock.xml"))
        val elements = (0 until document.getElementsByTagName("TextView").length)
            .map { document.getElementsByTagName("TextView").item(it) as Element }
        val byId = elements.associateBy { it.getAttribute("android:id") }

        listOf("btnDockToneDown", "btnDockToneUp", "btnDockDownloads").forEach { id ->
            val control = byId.getValue("@+id/$id")
            assertEquals("visible", control.getAttribute("android:visibility"))
            assertEquals("true", control.getAttribute("android:focusable"))
        }
        assertEquals("gone", byId.getValue("@+id/btnDockHistory").getAttribute("android:visibility"))
        assertEquals("@id/btnDockToneDown", byId.getValue("@+id/btnDockVolUp").getAttribute("android:nextFocusRight"))
        assertEquals("@id/btnDockDownloads", byId.getValue("@+id/btnDockToneUp").getAttribute("android:nextFocusRight"))
        assertEquals("@id/btnDockQueue", byId.getValue("@+id/btnDockDownloads").getAttribute("android:nextFocusRight"))

        val browser = locate("src/main/res/layout/view_ktv_three_column_browser.xml").readText()
        assertTrue("历史入口必须继续留在 Home KTV 浏览器中", browser.contains("@+id/browserNavHistory"))
        val activity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(activity.contains("Home KTV 没有歌曲下载任务"))
        assertTrue(activity.contains("onPitchDown = { onPitchStep(-1) }"))
        assertTrue(activity.contains("onPitchUp = { onPitchStep(1) }"))
        assertTrue(activity.contains("activeEngine.setPitchSemitones"))
        assertTrue(activity.contains("KtvPitchDialog("))
        assertTrue(!activity.contains("showPitchSupportDrawer"))
        val controller = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        assertTrue(controller.contains("binding.btnDockDownloads.setOnClickListener { actions.onDownloads() }"))
        assertTrue(controller.contains("binding.btnDockToneDown.setOnClickListener { actions.onPitchDown() }"))
        assertTrue(controller.contains("binding.btnDockToneUp.setOnClickListener { actions.onPitchUp() }"))
    }

    @Test
    fun queueCountStartsUnknownAndQueueDrawerDoesNotTreatDefaultStateAsAnEmptySnapshot() {
        val mainActivity = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val initialDock = mainActivity.substringAfter("bottomDockController.render(")
            .substringBefore("setContentView(binding.root)")
        assertTrue(initialDock.contains("queueCountKnown = false"))
        assertTrue(initialDock.contains("queueSnapshotFresh = false"))

        val overlay = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        assertTrue(overlay.contains("!state.queueLoading && state.errorFor(com.homektv.tv.controller.UiDomain.QUEUE) == null"))
        assertTrue(overlay.contains("state.queue.takeIf {"))
        assertTrue(overlay.contains("!state.queueLoading && queueError == null"))
        assertTrue(overlay.contains("dialog.showUnavailable(message, retryAvailable = !state.queueLoading)"))
        assertTrue(overlay.contains("dialog.onRetryQueue = controllerActions::refreshQueue"))
        assertTrue(!overlay.contains("state.queue.takeIf { state.connection != ControllerConnection.CONNECTING }"))
    }

    @Test
    fun fullscreenPanelAndMainActivityWirePitchAndUnifiedUnavailableDialog() {
        val fullscreenXml = locate("src/main/res/layout/view_ktv_fullscreen_playback_panel.xml").readText()
        assertTrue(fullscreenXml.contains("android:id=\"@+id/btnFullscreenPitch\""))

        val panelSource = locate("src/main/java/com/homektv/tv/ui/KtvFullscreenPlaybackPanel.kt").readText()
        val moveFocusMethod = panelSource.substringAfter("private fun moveFocus(")
            .substringBefore("private fun currentFocusedButton()")
        val focusedButtonMethod = panelSource.substringAfter("private fun currentFocusedButton()")
            .substringBefore("private fun seekBy(")
        assertTrue(panelSource.contains("FullscreenPlaybackAction.PITCH"))
        assertTrue(moveFocusMethod.contains("pitchButton"))
        assertTrue(focusedButtonMethod.contains("pitchButton"))

        val activitySource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(activitySource.contains("FullscreenPlaybackAction.PITCH -> openPitchFromFullscreen()"))
        val unavailableMethod = activitySource.substringAfter("private fun showPitchUnavailable(")
            .substringBefore("private fun showCapabilityDrawer(")
        assertTrue("不可用状态也应使用居中 KtvPitchDialog 统一承载", unavailableMethod.contains("KtvPitchDialog("))
        assertTrue("不可用状态不得再退回 showCapabilityDrawer", !unavailableMethod.contains("showCapabilityDrawer("))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequence {
            yield(workingDirectory.resolve(relativePath))
            yield(workingDirectory.resolve("app/$relativePath"))
            yield(workingDirectory.resolve("../$relativePath"))
            yield(workingDirectory.resolve("../../$relativePath"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${workingDirectory.absolutePath}")
    }
}
