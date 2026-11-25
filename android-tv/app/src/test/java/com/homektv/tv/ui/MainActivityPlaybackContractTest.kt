package com.homektv.tv.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPlaybackContractTest {
    @Test
    fun productionPlaybackUsesRetryingCoordinatorForSourceResolution() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("PlaybackCoordinator("))
        assertTrue(main.contains("playbackCoordinator.replace("))
        assertTrue(main.contains("PlaybackReplacementRequest(queueId"))
        assertTrue(main.contains("source = object : PlaybackSource"))
    }

    @Test
    fun videoMediaTypeRemainsDeclaredWithoutResolutionMetadata() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("val hasVideoDeclared = !snapshot.playing?.song?.mediaType.equals(\"AUDIO\", ignoreCase = true)"))
        assertTrue(!main.contains("&& !file.resolution.isNullOrBlank()"))
        assertTrue(main.contains("error.code == \"FILE_NOT_FOUND\" || error.code == \"NO_VALID_FILE\""))
    }

    @Test
    fun sameQueueVocalChangeDoesNotReloadMedia() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("if (playing.queueId == currentQueueId && loadedQueueId == playing.queueId)"))
        assertTrue(main.contains("eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount, currentAudioLayout)"))
        assertTrue(main.contains("if (playbackSeekGate.shouldApply(snapshot.seekSequence))"))
    }

    @Test
    fun offlineAudioPreviewDoesNotInventSongLyricsOrPlaybackProgress() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val preview = main.substringAfter("private fun renderAudioPreview()")
            .substringBefore("override fun onResume()")

        assertFalse(preview.contains("晴天"))
        assertFalse(preview.contains("周杰伦"))
        assertFalse(preview.contains("海阔天空"))
        assertFalse(preview.contains("LyricLine("))
        assertFalse(preview.contains("01:42"))
        assertFalse(preview.contains("04:03"))
        assertTrue(preview.contains("R.string.audio_preview_empty_title"))
    }

    @Test
    fun kioskPipUsesTheGlobalDockPlayerWithoutStartingPlaybackAgain() {
        val kiosk = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val activeTransition = kiosk.substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringBefore("private fun renderPipState()")

        assertTrue(activeTransition.contains("bottomDockController.ensurePlayerInDock()"))
        assertFalse(activeTransition.contains("reparentView("))
        assertTrue(!activeTransition.contains("playbackCoordinator.replace("))
        assertTrue(!activeTransition.contains("engine?.play("))
    }

    @Test
    fun fullscreenBackRestoresTheSameDockPlayerAndKeepsKioskContext() {
        val dock = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        assertFalse("SurfaceView 绝不允许换宿主", dock.contains("removeView("))
        assertFalse("SurfaceView 绝不允许换宿主", dock.contains("addView("))
        assertFalse("SurfaceView 绝不允许换宿主", dock.contains("movePlayerView("))
        assertTrue("必须受外部 HDMI 异显保护", dock.contains("externalDisplayActive"))

        val kiosk = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val promptAction = kiosk.substringAfter("KioskBackAction.PROMPT_EXIT_WITH_FULLSCREEN")
            .substringBefore("KioskBackAction.RETURN_TO_FULLSCREEN_MV")
        assertTrue(promptAction.contains("onRequestExitConfirm(hasPlaying)"))
        assertFalse(promptAction.contains("toggleKiosk(false)"))

        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        assertTrue(main.contains("fullscreenPlaybackPanel.handleKeyEvent(event)"))
        assertTrue(main.contains("bottomDockController.exitFullscreen()"))
        assertTrue(main.contains("onRequestExitConfirm = ::showExitConfirmDialog"))
    }

    @Test
    fun surfaceMediaOverlayOrderIsConfiguredBeforeWindowAttachAndNotToggledDuringReparent() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val inflate = main.indexOf("binding = ActivityMainBinding.inflate(layoutInflater)")
        val configureLayer = main.indexOf("setZOrderMediaOverlay(true)")
        val attachWindow = main.indexOf("setContentView(binding.root)")

        assertTrue("player surface order must be configured after inflate", inflate >= 0 && configureLayer > inflate)
        assertTrue("player surface order must be configured before window attach", configureLayer in (inflate + 1) until attachWindow)

        val kiosk = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val transition = kiosk.substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringBefore("private fun renderPipState()")
        assertFalse("reparenting must not dynamically toggle SurfaceView media-overlay order", transition.contains("setZOrderMediaOverlay("))
    }

    @Test
    fun playbackPreparingDisplaysSingleStatusToastWithoutSpamming() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(main.contains("onPlaybackPreparing = { token, _ ->"))
        assertTrue(main.contains("if (playbackCoordinator.isCurrent(token)) onToast(\"正在准备 MV，请稍候\")"))
    }

    @Test
    fun coldStartDirectlyInitializesAndPresentsCommercialDashboard() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val onCreateBlock = main.substringAfter("override fun onCreate(")
            .substringBefore("override fun onResume()")
        assertTrue(
            "MainActivity.onCreate 必须在冷启动时展开 Kiosk 大厅",
            onCreateBlock.contains("ensureKioskController().apply") &&
                onCreateBlock.contains("toggleKiosk(true)")
        )

        val mainLayout = locate("src/main/res/layout/activity_main.xml")
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mainLayout)
        val elements = doc.getElementsByTagName("*")
        val standby = (0 until elements.length).map { elements.item(it) }
            .filterIsInstance<org.w3c.dom.Element>()
            .firstOrNull { it.getAttribute("android:id") == "@+id/standbyPanel" }
        assertNull("standbyPanel must be removed from activity_main.xml", standby)
    }

    @Test
    fun fullscreenControlsRevealWithoutExitingAndBackClosesInLayers() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val dispatchMethod = main.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean")
            .substringBefore("// ---- 音量 OSD ----")

        val panelRoute = dispatchMethod.indexOf("fullscreenPlaybackPanel.handleKeyEvent(event)")
        val kioskRoute = dispatchMethod.indexOf("kioskController.dispatchKeyEvent(event)")
        assertTrue("全屏播控必须先于点歌台键路由", panelRoute >= 0 && panelRoute < kioskRoute)
        assertFalse(
            "中心键/Enter 不得再直接退出全屏",
            dispatchMethod.contains("bottomDockController.isFullscreen && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER"),
        )
        assertTrue("面板 Back 由分层键路由负责", dispatchMethod.contains("fullscreenPlaybackPanel.handleKeyEvent(event)"))

        val dock = locate("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").readText()
        val enter = dock.substringAfter("fun enterFullscreen()")
            .substringBefore("fun exitFullscreen()")
        assertTrue("全屏进入仍复用 Dock 的同一播放器 bounds", enter.contains("applyWindowBounds(DockFullscreenPolicy.boundsFor(true))"))

        val panelLayout = locate("src/main/res/layout/view_ktv_fullscreen_playback_panel.xml")
        assertTrue("必须提供独立全屏播控布局", panelLayout.isFile)

        val showPlayer = main.substringAfter("private fun showPlayer(audioMode: Boolean = false)")
            .substringBefore("private fun showStandby()")
        assertFalse("全屏视频换歌不得被强制缩回 Dock", showPlayer.contains("ensurePlayerInDock()"))
        val standby = main.substringAfter("private fun showStandby()")
            .substringBefore("private fun showPlaybackProgress()")
        assertTrue("进入待机前必须完整退出全屏 bounds", standby.contains("bottomDockController.exitFullscreen()"))
    }

    @Test
    fun modalOverlaysInterceptBackBeforeKioskController() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val dispatchMethod = main.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean")
            .substringBefore("// ---- 音量 OSD ----")

        val remoteMenuIndex = dispatchMethod.indexOf("remoteMenuBinding?.root?.visibility == View.VISIBLE")
        val kioskDispatchIndex = dispatchMethod.indexOf("kioskController.dispatchKeyEvent(event)")
        assertTrue("remoteMenuBinding 处理必须存在于 dispatchKeyEvent 中", remoteMenuIndex >= 0)
        assertTrue("kioskController.dispatchKeyEvent 必须存在于 dispatchKeyEvent 中", kioskDispatchIndex >= 0)
        assertTrue(
            "调音面板等浮层在显示时必须在 kioskController 之前消费 Back 键，防止死锁或误退大厅",
            remoteMenuIndex < kioskDispatchIndex,
        )
    }

    @Test
    fun volumeAndVocalOsdsBringToFrontAndHandwritingBackClearsInk() {
        val main = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val volumeOsdFn = main.substringAfter("private fun showVolumeOsd(")
            .substringBefore("private fun showVocalOsd(")
        val vocalOsdFn = main.substringAfter("private fun showVocalOsd(")
            .substringBefore("private fun resetIdleTimer(")
        assertTrue("showVolumeOsd 必须置顶防止被全屏或点歌浮层遮挡", volumeOsdFn.contains("binding.volumeOsd.bringToFront()"))
        assertTrue("showVocalOsd 必须置顶防止被全屏或点歌浮层遮挡", vocalOsdFn.contains("binding.vocalOsd.bringToFront()"))

        val keyboard = locate("src/main/java/com/homektv/tv/ui/KtvKeyboardView.kt").readText()
        val backFn = keyboard.substringAfter("fun consumeBackForActiveDrawing(action: Int): Boolean")
            .substringBefore("private fun clearTransientInputState()")
        assertTrue("手写画板存在笔迹时按返回键必须先清空笔迹而非直接退页", backFn.contains("hasInk()") && backFn.contains("clearInk()"))
    }

    @Test
    fun alertDialogsAndScanConfirmUseDarkTvTheme() {
        val mainSource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val overlaySource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val scanXml = locate("src/main/res/layout/dialog_library_scan_confirm.xml").readText()

        assertFalse("checkModeMigration 已随多模式切换移除", mainSource.contains("checkModeMigration"))

        val imeBlock = overlaySource.substringAfter("private fun openSystemImeSearchDialog").substringBefore("private fun onRemoteMenuPressed")
        assertTrue("系统输入法搜索弹窗必须使用暗色 TV 弹窗主题", imeBlock.contains("Theme_HomeKtvTv_UpdateDialog"))
        assertTrue("系统输入法输入框应设置白色前景以适配暗色弹窗", imeBlock.contains("Color.WHITE"))

        assertTrue("更新歌库确认框应使用统一暗色玻璃背景", scanXml.contains("@drawable/player_glass"))
        assertFalse("更新歌库确认框不得使用白底 bg_library_scan_dialog", scanXml.contains("@drawable/bg_library_scan_dialog"))
    }

    @Test
    fun modalLifecycleSuspendsBackgroundFocusAndSkipsRestoreInTouchMode() {
        val mainSource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val trackBlock = mainSource.substringAfter("private fun trackModalDialog").substringBefore("private fun untrackModalDialog")
        val untrackBlock = mainSource.substringAfter("private fun untrackModalDialog").substringBefore("internal fun showTrackedAlertDialog")
        val restoreBlock = mainSource.substringAfter("private fun restoreFocus").substringBefore("private fun trackModalDialog")

        assertTrue("trackModalDialog 必须挂起底层页面焦点，防止弹窗后方按钮残留焦点框", trackBlock.contains("suspendBackgroundFocusForModal()"))
        assertTrue("untrackModalDialog 必须在无弹窗时恢复底层页面焦点能力", untrackBlock.contains("restoreBackgroundFocusAfterModal()"))
        assertTrue("restoreFocus 在触控模式下不得强行抢占焦点框", restoreBlock.contains("!target.isInTouchMode"))
    }

    @Test
    fun coordinatorTerminalFailuresBypassReplacementGuardAndClearJob() {
        val mainSource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val missingBlock = mainSource.substringAfter("onMissingSource = { token ->")
            .substringBefore("onPlaybackPreparing =")
        val failureBlock = mainSource.substringAfter("onSourceFailure = { token, error ->")
            .substringBefore("onSourceExhausted =")
        val exhaustedBlock = mainSource.substringAfter("onSourceExhausted = { token, _ ->")
            .substringBefore("updateManager = AndroidUpdateManager(")

        assertTrue(missingBlock.contains("playbackReplacementJob = null") && missingBlock.contains("bypassReplacementGuard = true"))
        assertTrue(failureBlock.contains("playbackReplacementJob = null") && failureBlock.contains("bypassReplacementGuard = true"))
        assertTrue(exhaustedBlock.contains("playbackReplacementJob = null") && exhaustedBlock.contains("bypassReplacementGuard = true"))
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
