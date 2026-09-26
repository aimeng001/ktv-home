package com.homektv.tv.ui

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.SystemClock
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.player.DesiredPlaybackState
import com.homektv.tv.session.DeviceMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs a synthetic dual-track MV through the production Media3 engine and PiP/presentation views. */
@RunWith(AndroidJUnit4::class)
class PlaybackSimulationInstrumentedTest {
    private val runId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    @Test
    fun syntheticMvPreservesPlaybackAcrossPipPauseVocalBufferAndVirtualDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTelevision) return

        val realMvUrl = InstrumentationRegistry.getArguments().getString("realMvUrl")
        if (!realMvUrl.isNullOrBlank()) {
            realSourceMvPlaysThroughProductionEngine(
                instrumentation = instrumentation,
                context = context,
                streamUrl = realMvUrl,
            )
            return
        }

        val mediaBytes = instrumentation.context.assets.open("ktv_synthetic_dual_track.mp4").use { it.readBytes() }
        val mediaServer = SlowLocalMediaServer(mediaBytes).start()
        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)

        var scenario: ActivityScenario<MainActivity>? = null
        var imageReader: ImageReader? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                // The app's normal offline-role callback correctly clears playback. Stop
                // this test-only connection first so it cannot race with the synthetic song.
                privateField<KtvSocket?>(activity, "socket")?.close()
                setPrivateField(activity, "socket", null)
            }

            val firstReady = CountDownLatch(1)
            val firstFrame = CountDownLatch(1)
            val bufferingAfterReady = CountDownLatch(1)
            val playerFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
            lateinit var engine: PlaybackEngine
            lateinit var player: Player
            lateinit var kiosk: KtvKioskOverlayController
            lateinit var mainBinding: ActivityMainBinding
            val audioLayout = AudioLayout(
                layout = "DUAL_TRACK",
                originalTrackIndex = 0,
                accompanimentTrackIndex = 1,
            )
            val firstSong = SongDto(
                id = 91001L,
                title = "合成播放测试片段",
                artist = "本地仪器夹具",
                mediaType = "MV",
                hasVocalTrack = true,
                durationMs = 30_000,
            )
            val firstSnapshot = QueueSnapshot(
                playing = NowPlaying(queueId = 501L, song = firstSong),
                state = "playing",
                vocalMode = "original",
                audioLayout = audioLayout,
            )

            scenario.onActivity { activity ->
                engine = privateField(activity, "engine")
                mainBinding = privateField(activity, "binding")
                kiosk = privateField(activity, "kioskController")
                player = requireNotNull(mainBinding.playerView.player)
                var reachedReady = false
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerFailure.set(error.toString())
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            reachedReady = true
                            firstReady.countDown()
                        } else if (reachedReady && playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                            bufferingAfterReady.countDown()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrame.countDown()
                    }
                })
                kiosk.updateSnapshot(firstSnapshot)
                privateField<Any>(activity, "hasCurrentSong").let { setPrivateField(activity, "hasCurrentSong", true) }
                setPrivateField(activity, "currentPlaybackState", "playing")
                setPrivateField(activity, "currentAudioLayout", audioLayout)
                setPrivateField(activity, "audioTrackCount", 2)
                kiosk.toggleKiosk(false)
                mainBinding.playerView.visibility = android.view.View.VISIBLE
                mainBinding.standbyPanel.visibility = android.view.View.GONE
                engine.play(
                    fileId = 91001L,
                    streamUrl = mediaServer.liveTranscodeUrl,
                    queueId = 501L,
                    hasVideoDeclared = true,
                    format = "mp4",
                    videoCodec = "h264",
                    songDurationMs = 30_000L,
                )
            }

            if (!firstReady.await(45, TimeUnit.SECONDS)) {
                val details = readActivity(scenario) { activity ->
                    val currentPlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                    "state=${currentPlayer?.playbackState}, loading=${currentPlayer?.isLoading}, " +
                        "error=${playerFailure.get()}, requests=${mediaServer.requestCount}, " +
                        "last=${mediaServer.lastRequestLine}"
                }
                assertTrue("Media3 should reach READY from the local synthetic MV; $details", false)
            }
            assertTrue("the AVD decoder should render a synthetic video frame", firstFrame.await(10, TimeUnit.SECONDS))
            assertTrue("synthetic MV should have two audio tracks", awaitActivityCondition(scenario, 5_000L) { activity ->
                val currentPlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                currentPlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }?.sumOf { it.length } ?: 0 >= 2
            })

            scenario.onActivity {
                engine.setVocalMode("original", 1, 2, audioLayout)
            }
            assertTrue("original audio group should be selected", awaitSelectedAudioGroup(scenario, 0))
            val beforeVocalSwitch = readActivity(scenario) { engine.currentPositionMs }
            scenario.onActivity {
                engine.setVocalMode("accompaniment", 1, 2, audioLayout)
            }
            assertTrue("accompaniment audio group should be selected", awaitSelectedAudioGroup(scenario, 1))
            assertEquals("vocal switching must not reload the current media", "91001", readActivity(scenario) {
                privateField<ActivityMainBinding>(it, "binding").playerView.player?.currentMediaItem?.mediaId
            })
            assertTrue("vocal switching must not reset playback progress", readActivity(scenario) {
                engine.currentPositionMs + 250L >= beforeVocalSwitch
            })

            val livePipeSeekPositionMs = 6_000L
            scenario.onActivity { engine.seekTo(livePipeSeekPositionMs) }
            assertTrue("live pipe seek should request a new URL with the absolute start offset", awaitActivityCondition(scenario, 15_000L) { activity ->
                val currentPlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                mediaServer.lastRequestLine?.contains("transcode=true&start=6.000") == true &&
                    currentPlayer?.playbackState == Player.STATE_READY &&
                    engine.currentPositionMs >= livePipeSeekPositionMs &&
                    currentPlayer.currentMediaItem?.mediaId == "91001"
            })
            assertTrue("live pipe seek should preserve the selected accompaniment track", awaitSelectedAudioGroup(scenario, 1))

            scenario.onActivity { engine.pause() }
            assertTrue("live pipe should pause before the second seek", awaitActivityCondition(scenario, 3_000L) { activity ->
                privateField<ActivityMainBinding>(activity, "binding").playerView.player?.playWhenReady == false
            })
            val pausedLivePipeSeekPositionMs = 9_000L
            scenario.onActivity { engine.seekTo(pausedLivePipeSeekPositionMs) }
            assertTrue("a paused live pipe seek should reopen at the target and stay paused", awaitActivityCondition(scenario, 15_000L) { activity ->
                val currentPlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                mediaServer.lastRequestLine?.contains("transcode=true&start=9.000") == true &&
                    currentPlayer?.playbackState == Player.STATE_READY &&
                    currentPlayer.playWhenReady == false &&
                    engine.currentPositionMs >= pausedLivePipeSeekPositionMs
            })
            scenario.onActivity { engine.resume() }
            assertTrue("live pipe should resume after the paused seek", awaitActivityCondition(scenario, 3_000L) { activity ->
                privateField<ActivityMainBinding>(activity, "binding").playerView.player?.playWhenReady == true
            })

            capture("25_mv_playback_synthetic_fullscreen.png")
            val positionBeforePip = readActivity(scenario) { engine.currentPositionMs }
            scenario.onActivity {
                // ControllerActions publishes its initial empty queue asynchronously; publish
                // the synthetic active-song snapshot after that startup state has settled.
                kiosk.updateSnapshot(firstSnapshot)
                kiosk.toggleKiosk(true)
            }
            assertTrue("kiosk state collector should enter song-selection mode", awaitActivityCondition(scenario, 5_000L) {
                privateField<KtvFocusController>(privateField(it, "kioskController"), "focusController").isKioskActive
            })
            assertTrue("same PlayerView should move into the kiosk PiP anchor", awaitActivityCondition(scenario, 5_000L) { activity ->
                val binding = privateField<ActivityMainBinding>(activity, "binding")
                val overlay = privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding")
                binding.playerView.parent === overlay.pipVideoAnchor
            })
            val pipVisible = awaitActivityCondition(scenario, 5_000L) {
                privateField<ViewKtvKioskOverlayBinding>(it, "kioskOverlayBinding").pipVideoFrame.visibility == android.view.View.VISIBLE
            }
            if (!pipVisible) {
                val details = readActivity(scenario) { activity ->
                    val overlay = privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding")
                    val kiosk = privateField<KtvKioskOverlayController>(activity, "kioskController")
                    val stage = overlay.kioskContentStage
                    "stage=${stage.width}x${stage.height} padding=${stage.paddingLeft},${stage.paddingRight},${stage.paddingTop},${stage.paddingBottom} " +
                        "space=${privateField<Boolean>(kiosk, "pipSpaceAvailable")} root=${overlay.kioskRootOverlay.visibility} " +
                        "frame=${overlay.pipVideoFrame.visibility} label=${overlay.txtPipLabel.text}"
                }
                assertTrue("PiP frame should become visible after kiosk layout measurement; $details", pipVisible)
            }
            scenario.onActivity { activity ->
                val overlay = privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding")
                assertSame("PiP must retain the same Media3 player", player, mainBinding.playerView.player)
                assertEquals(
                    "accompaniment track should survive fullscreen/PiP reparenting",
                    listOf(1),
                    selectedAudioGroupIndexes(player),
                )
            }
            capture("07_dashboard_playing_pip_synthetic.png")
            assertTrue("PiP transition must not reset the active song", readActivity(scenario) {
                engine.currentPositionMs + 250L >= positionBeforePip
            })
            assertTrue("position should remain attached to the same queue item", readActivity(scenario) {
                privateField<ActivityMainBinding>(it, "binding").playerView.player?.currentMediaItem?.mediaId == "91001"
            })

            scenario.onActivity {
                engine.pause()
                kiosk.updateSnapshot(firstSnapshot.copy(state = "paused", vocalMode = "accompaniment"))
            }
            assertTrue("pause should clear playWhenReady", awaitActivityCondition(scenario, 3_000L) { activity ->
                privateField<ActivityMainBinding>(activity, "binding").playerView.player?.playWhenReady == false
            })
            val pausedPosition = readActivity(scenario) { engine.currentPositionMs }
            SystemClock.sleep(800L)
            assertTrue("paused position must remain stable", readActivity(scenario) {
                kotlin.math.abs(engine.currentPositionMs - pausedPosition) <= 200L
            })
            scenario.onActivity { activity ->
                val overlay = privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding")
                assertEquals(
                    "小窗应同时显示暂停状态与当前真实歌曲信息",
                    "已暂停  合成播放测试片段 · 本地仪器夹具",
                    overlay.txtPipLabel.text.toString(),
                )
                assertEquals(android.view.View.VISIBLE, overlay.pipVideoFrame.visibility)
            }
            capture("07_dashboard_paused_pip_synthetic.png")

            scenario.onActivity {
                engine.resume()
                kiosk.updateSnapshot(firstSnapshot.copy(state = "playing", vocalMode = "accompaniment"))
            }
            assertTrue("buffering should be observable after READY on the throttled local stream", bufferingAfterReady.await(20, TimeUnit.SECONDS))
            assertTrue("PiP should show its buffering state", awaitActivityCondition(scenario, 3_000L) { activity ->
                privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding").txtPipLabel.text.toString() ==
                    "缓冲中  合成播放测试片段 · 本地仪器夹具"
            })

            // The production kiosk intentionally exits after 15 seconds without user input.
            // Simulate the user remaining in song selection before exercising the display event.
            scenario.onActivity {
                kiosk.updateSnapshot(firstSnapshot.copy(state = "playing", vocalMode = "accompaniment"))
                kiosk.toggleKiosk(true)
            }
            assertTrue("kiosk should remain active before the external-display transition", awaitActivityCondition(scenario, 5_000L) {
                privateField<KtvFocusController>(privateField(it, "kioskController"), "focusController").isKioskActive
            })
            assertTrue("buffering PiP should be visible while the user remains in song selection", awaitActivityCondition(scenario, 3_000L) {
                privateField<ViewKtvKioskOverlayBinding>(it, "kioskOverlayBinding").pipVideoFrame.visibility == android.view.View.VISIBLE
            })
            capture("07_dashboard_buffering_pip_synthetic.png")

            imageReader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 2)
            val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager
            virtualDisplay = displayManager.createVirtualDisplay(
                "KTV simulated presentation",
                640,
                360,
                160,
                imageReader!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            )
            assertNotNull("AVD should provide a virtual presentation display", virtualDisplay)
            assertTrue("virtual display should be marked for Presentation", virtualDisplay!!.display.flags and Display.FLAG_PRESENTATION != 0)
            assertTrue("Presentation lifecycle should attach the existing player", awaitActivityCondition(scenario, 5_000L) {
                privateField<Boolean>(it, "externalDisplayActive")
            })
            scenario.onActivity { activity ->
                val externalPlayerView = privateField<androidx.media3.ui.PlayerView?>(activity, "attachedExternalPlayerView")
                assertNotNull(externalPlayerView)
                assertSame("external output must attach the same player", player, externalPlayerView?.player)
                assertEquals(android.view.View.INVISIBLE, mainBinding.playerView.visibility)
                val overlay = privateField<ViewKtvKioskOverlayBinding>(activity, "kioskOverlayBinding")
                val kioskExternal = privateField<Boolean>(privateField(activity, "kioskController"), "externalDisplayActive")
                val focusController = privateField<com.homektv.tv.ui.KtvFocusController>(privateField(activity, "kioskController"), "focusController")
                assertEquals(
                    "HDMI should hide local PiP; activityExternal=${privateField<Boolean>(activity, "externalDisplayActive")}, " +
                        "kioskExternal=$kioskExternal, kioskActive=${focusController.isKioskActive}, " +
                        "label=${overlay.txtPipLabel.text}",
                    android.view.View.GONE,
                    overlay.pipVideoFrame.visibility,
                )
                assertEquals(
                    "外接屏提示应保留播放项信息",
                    "视频正在外接屏播放  合成播放测试片段 · 本地仪器夹具",
                    overlay.txtPipLabel.text.toString(),
                )
            }
            capture("07_dashboard_external_display_synthetic.png")
            val positionBeforeDisplayRemoval = readActivity(scenario) { engine.currentPositionMs }
            virtualDisplay!!.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            assertTrue("removing the virtual display should reattach the original PlayerView", awaitActivityCondition(scenario, 5_000L) {
                !privateField<Boolean>(it, "externalDisplayActive") &&
                    privateField<ActivityMainBinding>(it, "binding").playerView.player === player
            })
            assertTrue("virtual display changes must not restart media", readActivity(scenario) {
                engine.currentPositionMs + 250L >= positionBeforeDisplayRemoval
            })
            capture("07_dashboard_pip_restored_synthetic.png")

            val nextSong = firstSong.copy(id = 91002L, title = "合成切歌目标")
            scenario.onActivity {
                kiosk.updateSnapshot(
                    firstSnapshot.copy(
                        playing = NowPlaying(queueId = 502L, song = nextSong),
                        state = "playing",
                        vocalMode = "original",
                    ),
                )
                engine.play(
                    fileId = 91002L,
                    streamUrl = mediaServer.url,
                    queueId = 502L,
                    hasVideoDeclared = true,
                    format = "mp4",
                    videoCodec = "h264",
                    songDurationMs = 30_000L,
                )
            }
            assertTrue("next song should replace the media identity", awaitActivityCondition(scenario, 3_000L) {
                privateField<ActivityMainBinding>(it, "binding").playerView.player?.currentMediaItem?.mediaId == "91002"
            })
            assertTrue("next song replacement should start near its beginning", readActivity(scenario) {
                engine.currentPositionMs <= 1_500L
            })
            capture("25_next_mv_synthetic.png")
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
            scenario?.close()
            mediaServer.close()
            config.saveMode(previousMode, previousHost)
        }
    }

    private fun realSourceMvPlaysThroughProductionEngine(
        instrumentation: android.app.Instrumentation,
        context: android.content.Context,
        streamUrl: String,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        val format = arguments.getString("realMvFormat") ?: "mkv"
        val videoCodec = arguments.getString("realMvVideoCodec") ?: "h264"
        val audioTrackCount = arguments.getString("realMvAudioTracks")?.toIntOrNull() ?: 1
        val durationMs = arguments.getString("realMvDurationMs")?.toLongOrNull() ?: 0L
        val audioLayout = if (audioTrackCount > 1) {
            AudioLayout(
                layout = "DUAL_TRACK",
                originalTrackIndex = 0,
                accompanimentTrackIndex = 1,
            )
        } else {
            AudioLayout.normalStereo()
        }
        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val apiHost = arguments.getString("realMvApiHost") ?: "127.0.0.1:9"
        config.serverHost = apiHost
        config.saveMode(DeviceMode.COMBINED, apiHost)

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                privateField<KtvSocket?>(activity, "socket")?.close()
                setPrivateField(activity, "socket", null)
            }

            val ready = CountDownLatch(1)
            val firstFrame = CountDownLatch(1)
            val playbackFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
            lateinit var engine: PlaybackEngine
            lateinit var player: Player
            scenario.onActivity { activity ->
                engine = privateField(activity, "engine")
                val binding = privateField<ActivityMainBinding>(activity, "binding")
                val kiosk = privateField<KtvKioskOverlayController>(activity, "kioskController")
                player = requireNotNull(binding.playerView.player)
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackFailure.set(error.toString())
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrame.countDown()
                    }
                })
                val song = SongDto(
                    id = 91003L,
                    title = "只读曲库实曲播放验收",
                    artist = "本地只读样本",
                    mediaType = "MV",
                    hasVocalTrack = audioTrackCount > 1,
                    durationMs = durationMs.toInt(),
                )
                val snapshot = QueueSnapshot(
                    playing = NowPlaying(queueId = 503L, song = song),
                    state = "playing",
                    vocalMode = "original",
                    audioLayout = audioLayout,
                )
                privateField<DesiredPlaybackState>(activity, "desiredPlaybackState").update(snapshot)
                kiosk.updateSnapshot(snapshot)
                setPrivateField(activity, "hasCurrentSong", true)
                setPrivateField(activity, "currentPlaybackState", "playing")
                setPrivateField(activity, "currentAudioLayout", audioLayout)
                setPrivateField(activity, "audioTrackCount", audioTrackCount)
                kiosk.toggleKiosk(false)
                binding.playerView.visibility = android.view.View.VISIBLE
                binding.standbyPanel.visibility = android.view.View.GONE
                engine.play(
                    fileId = 91003L,
                    streamUrl = streamUrl,
                    queueId = 503L,
                    hasVideoDeclared = true,
                    format = format,
                    videoCodec = videoCodec,
                    songDurationMs = durationMs,
                )
            }

            if (!ready.await(90, TimeUnit.SECONDS)) {
                assertTrue("real read-only MV should reach Media3 READY; error=" + playbackFailure.get(), false)
            }
            assertTrue("real read-only MV should render a video frame; error=" + playbackFailure.get(), firstFrame.await(30, TimeUnit.SECONDS))
            if (audioTrackCount > 0) {
                assertTrue("real MV audio track metadata should be available", awaitActivityCondition(scenario, 15_000L) {
                    player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length } >= audioTrackCount
                })
            }

            if (audioTrackCount > 1) {
                scenario.onActivity { activity ->
                    // Match the production order: a vocal-mode change is first published in the
                    // authoritative queue snapshot, then applied to the player. Updating only
                    // PlaybackEngine here creates an impossible split-brain state that the
                    // production load projection correctly resolves in favor of the snapshot.
                    val desiredState = privateField<DesiredPlaybackState>(activity, "desiredPlaybackState")
                    val latest = desiredState.forQueue(503L)
                        ?: error("real-MV fixture lost its active queue snapshot")
                    val accompanimentSnapshot = latest.copy(
                        vocalMode = "accompaniment",
                        stateRevision = latest.stateRevision + 1L,
                    )
                    desiredState.update(accompanimentSnapshot)
                    privateField<KtvKioskOverlayController>(activity, "kioskController")
                        .updateSnapshot(accompanimentSnapshot)
                    engine.setVocalMode("accompaniment", 1, audioTrackCount, audioLayout)
                }
                val accompanimentSelected = awaitSelectedAudioGroup(scenario, 1)
                val selectionDiagnostics = if (accompanimentSelected) "" else readActivity(scenario) { activity ->
                    val activeEngine = privateField<PlaybackEngine>(activity, "engine")
                    val currentPlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                    val groups = currentPlayer?.currentTracks?.groups
                        ?.filter { it.type == C.TRACK_TYPE_AUDIO }
                        ?.mapIndexed { index, group ->
                            "$index:selected=${(0 until group.length).any(group::isTrackSelected)},supported=${group.isSupported}"
                        }
                        .orEmpty()
                    "groups=$groups requested=${privateField<String?>(activeEngine, "requestedVocalMode")}" +
                        " applied=${privateField<String?>(activeEngine, "appliedSelectionMode")}" +
                        " appliedGroup=${privateField<Any?>(activeEngine, "appliedSelectionGroup")}"
                }
                assertTrue("real dual-track MV should select accompaniment; $selectionDiagnostics", accompanimentSelected)
                val beforeVocalChange = readActivity(scenario) { engine.currentPositionMs }
                assertTrue("vocal switching must not reset the real MV position", awaitActivityCondition(scenario, 5_000L) {
                    engine.currentPositionMs >= beforeVocalChange + 500L
                })
            }

            val seekTargetMs = 20_000L
            scenario.onActivity { engine.seekTo(seekTargetMs) }
            assertTrue("real MKV Range seek should reach its requested position", awaitActivityCondition(scenario, 30_000L) {
                player.playbackState == Player.STATE_READY && engine.currentPositionMs >= seekTargetMs
            })
            if (audioTrackCount > 1) {
                val accompanimentRetained = awaitSelectedAudioGroup(scenario, 1)
                val seekSelectionDiagnostics = if (accompanimentRetained) "" else readActivity(scenario) { activity ->
                    val activeEngine = privateField<PlaybackEngine>(activity, "engine")
                    val activePlayer = privateField<ActivityMainBinding>(activity, "binding").playerView.player
                    val groups = activePlayer?.currentTracks?.groups
                        ?.filter { it.type == C.TRACK_TYPE_AUDIO }
                        ?.mapIndexed { index, group ->
                            "$index:selected=${(0 until group.length).any(group::isTrackSelected)},supported=${group.isSupported}"
                        }
                        .orEmpty()
                    val desired = privateField<DesiredPlaybackState>(activity, "desiredPlaybackState").forQueue(503L)
                    "groups=$groups requested=${privateField<String?>(activeEngine, "requestedVocalMode")}" +
                        " applied=${privateField<String?>(activeEngine, "appliedSelectionMode")}" +
                        " snapshot=${desired?.vocalMode} revision=${desired?.stateRevision}"
                }
                assertTrue("real MV Seek should retain the selected accompaniment; $seekSelectionDiagnostics", accompanimentRetained)
            }

            scenario.onActivity { engine.pause() }
            assertTrue("real MV should pause", awaitActivityCondition(scenario, 3_000L) { !player.playWhenReady })
            val pausedPosition = readActivity(scenario) { engine.currentPositionMs }
            SystemClock.sleep(700L)
            assertTrue("paused real MV position should remain stable", readActivity(scenario) {
                kotlin.math.abs(engine.currentPositionMs - pausedPosition) <= 250L
            })
            scenario.onActivity { engine.resume() }
            assertTrue("real MV should resume", awaitActivityCondition(scenario, 3_000L) { player.playWhenReady })
            capture("real_mv_read_only_source_smoke.png")
        } finally {
            scenario?.close()
            config.saveMode(previousMode, previousHost)
        }
    }

    private fun awaitSelectedAudioGroup(scenario: ActivityScenario<MainActivity>, groupIndex: Int): Boolean =
        awaitActivityCondition(scenario, 5_000L) { activity ->
            selectedAudioGroupIndexes(privateField<ActivityMainBinding>(activity, "binding").playerView.player) == listOf(groupIndex)
        }

    private fun selectedAudioGroupIndexes(player: Player?): List<Int> = player?.currentTracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .withIndex()
        .filter { (_, group) -> (0 until group.length).any(group::isTrackSelected) }
        .map { (index, _) -> index }

    private fun awaitActivityCondition(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        predicate: (MainActivity) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { readActivity(scenario, predicate) }.getOrDefault(false)) return true
            SystemClock.sleep(100L)
        }
        return runCatching { readActivity(scenario, predicate) }.getOrDefault(false)
    }

    private fun <T> readActivity(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
        var result: Any? = null
        scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun capture(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir("ui-evidence")), runId)
            .apply { check(mkdirs() || isDirectory) }
        FileOutputStream(File(directory, fileName)).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(owner: Any, name: String): T = owner.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner) as T

    private fun setPrivateField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private class SlowLocalMediaServer(private val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        @Volatile private var closed = false
        @Volatile var requestCount = 0
            private set
        @Volatile var lastRequestLine: String? = null
            private set
        private val acceptThread = Thread({ acceptLoop() }, "ktv-test-media-accept").apply {
            isDaemon = true
            start()
        }

        val url: String get() = "http://127.0.0.1:${server.localPort}/ktv_synthetic_dual_track.mp4"
        val liveTranscodeUrl: String get() = url + "?transcode=true"

        fun start(): SlowLocalMediaServer = this

        private fun acceptLoop() {
            while (!closed) {
                try {
                    val client = server.accept()
                    clients += client
                    Thread({ serve(client) }, "ktv-test-media-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!closed) SystemClock.sleep(20L)
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 8_000
                val input = BufferedInputStream(client.getInputStream())
                val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                requestCount += 1
                lastRequestLine = requestLine
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                if (!requestLine.startsWith("GET ")) return

                val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                val start = range?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, bytes.size - 1) ?: 0
                val requestedEnd = range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex
                val end = requestedEnd.coerceIn(start, bytes.lastIndex)
                val length = end - start + 1
                val output = BufferedOutputStream(client.getOutputStream())
                val status = if (range == null) "200 OK" else "206 Partial Content"
                output.write("HTTP/1.1 $status\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write("Content-Type: video/mp4\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write("Accept-Ranges: bytes\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                if (range != null) {
                    output.write("Content-Range: bytes $start-$end/${bytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                }
                output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()

                var offset = start
                while (!closed && offset <= end) {
                    val count = minOf(4_096, end - offset + 1)
                    output.write(bytes, offset, count)
                    output.flush()
                    offset += count
                    if (offset <= end) Thread.sleep(500L)
                }
            } catch (_: Exception) {
                // Player cancellation during pause/replacement is expected.
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
            clients.toList().forEach { runCatching { it.close() } }
            acceptThread.join(1_000L)
        }
    }
}
