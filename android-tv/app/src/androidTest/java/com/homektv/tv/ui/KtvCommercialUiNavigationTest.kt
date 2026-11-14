package com.homektv.tv.ui

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SavedServer
import com.homektv.tv.net.SongDto
import com.homektv.tv.net.UserProfile
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceModeMigrationPolicy
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end D-pad, song order, PiP/fullscreen, and TV resolution smoke test. */
@RunWith(AndroidJUnit4::class)
class KtvCommercialUiNavigationTest {
    private val runId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    @Test
    fun tvRemoteOrdersSongAndReturnsFromFullscreenAcrossSupportedResolutionProfiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("this acceptance test requires an Android TV AVD", isTelevision(context))

        val mediaBytes = instrumentation.context.assets.open("ktv_synthetic_dual_track.mp4").use { it.readBytes() }
        val fixture = KtvNavigationFixtureServer(mediaBytes).start()
        val firstSong = SongDto(
            id = 91001L,
            title = "测试播放片段",
            artist = "本地仪器夹具",
            mediaType = "MV",
            durationMs = 30_000,
        )
        val firstSnapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 501L, song = firstSong),
            state = "playing",
            vocalMode = "original",
            audioLayout = AudioLayout.normalStereo(),
            stateRevision = 1L,
        )
        // Keep the focus-graph sweep playback-idle. The kiosk legitimately dismisses
        // after 15 seconds without input while media is active; publish playback only
        // after the synthetic player has actually started below.
        fixture.setApiSnapshot(QueueSnapshot())
        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor(previousHost)
        val previousMigrationVersion = config.getModeMigrationVersion()
        assertFalse("the fixture server must not replace a saved endpoint", config.savedServers.any {
            it.hostPort == fixture.serverHost
        })
        assertFalse("the fixture endpoint must be distinct from the selected server", previousHost == fixture.serverHost)
        val fixtureHost = fixture.serverHost
        config.serverHost = fixture.serverHost
        config.saveMode(DeviceMode.COMBINED, fixture.serverHost)
        config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activityScenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = activityScenario
            // The dashboard and marquee contain ongoing animations, so a global idle barrier
            // can wait indefinitely. ActivityScenario.launch plus the fixture socket and the
            // explicit view predicates below provide the readiness checks this test needs.
            assertTrue("TV playback socket should connect to the local test fixture", fixture.awaitWebSocket())

            lateinit var activityWindow: Window
            val display = readActivity(activityScenario) { activity ->
                activityWindow = activity.window
                val size = Point()
                @Suppress("DEPRECATION")
                val defaultDisplay = activity.windowManager.defaultDisplay
                defaultDisplay.getRealSize(size)
                DisplayProfile(
                    physicalWidth = defaultDisplay.mode.physicalWidth,
                    physicalHeight = defaultDisplay.mode.physicalHeight,
                    logicalWidth = size.x,
                    logicalHeight = size.y,
                    densityDpi = activity.resources.displayMetrics.densityDpi,
                )
            }
            assertTrue(
                "unexpected physical TV resolution ${display.physicalWidth}x${display.physicalHeight}; " +
                    "expected 1280x720, 1920x1080, or 3840x2160",
                (display.physicalWidth to display.physicalHeight) in SUPPORTED_TV_RESOLUTIONS,
            )
            assertTrue(
                "unsupported Android TV logical viewport ${display.logicalWidth}x${display.logicalHeight}",
                (display.logicalWidth to display.logicalHeight) in SUPPORTED_TV_RESOLUTIONS,
            )
            assertEquals("the TV profiles must remain landscape", Configuration.ORIENTATION_LANDSCAPE,
                readActivity(activityScenario) { it.resources.configuration.orientation })

            // Combined TV mode auto-launches the kiosk in MainActivity.onCreate.
            // Sending OK here would activate the initially focused dashboard tile.
            assertTrue("the ten-tile dashboard should auto-open", awaitActivity(activityScenario) {
                it.findViewById<KtvCommercialDashboardView>(R.id.kioskDashboardView)?.isShown == true
            })
            assertTrue("the dashboard should initially focus the Hot ranking tile", awaitActivity(activityScenario) {
                it.currentFocus?.id == R.id.tileHotRanking
            })
            activityScenario.onActivity { activity ->
                val coordinator = privateField<KioskModeCoordinator>(activity, "kioskCoordinator")
                assertTrue("the kiosk should be active before navigation coverage", coordinator.isKioskActive.value)
                // This acceptance test deliberately spans network waits and screenshot exports.
                // Suspend the unrelated 15-second idle-dismiss policy so it cannot consume a
                // DPAD confirmation intended for the PiP focus target during slow AVD runs.
                coordinator.setModalActive(true)
            }
            captureScreenshot(activityWindow, context, "dashboard-before-navigation", display)
            activityScenario.onActivity { it.onUserInteraction() }
            assertBentoFocusGraph(activityScenario, instrumentation)
            assertVisibleDashboardTextFits(activityScenario)
            assertHeaderPageDockDoNotOverlap(activityScenario)

            lateinit var engine: PlaybackEngine
            lateinit var player: Player
            val playbackStarted = java.util.concurrent.CountDownLatch(1)
            activityScenario.onActivity { activity ->
                engine = privateField(activity, "engine")
                val mainBinding = privateField<ActivityMainBinding>(activity, "binding")
                player = requireNotNull(mainBinding.root.findViewById<PlayerView>(R.id.playerView).player)
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) playbackStarted.countDown()
                    }
                })

                // This fixture media is deliberately synthetic. It exercises the real Media3
                // PlayerView while keeping NAS data and the user's deployed server untouched.
                setPrivateField(activity, "currentQueueId", firstSnapshot.playing!!.queueId)
                setPrivateField(activity, "loadedQueueId", firstSnapshot.playing!!.queueId)
                setPrivateField(activity, "currentFileId", firstSong.id)
                setPrivateField(activity, "hasCurrentSong", true)
                setPrivateField(activity, "currentPlaybackState", "playing")
                setPrivateField(activity, "currentAudioLayout", firstSnapshot.audioLayout)
                setPrivateField(activity, "audioTrackCount", 1)
                engine.play(
                    fileId = firstSong.id,
                    streamUrl = fixture.videoUrl,
                    queueId = firstSnapshot.playing!!.queueId!!,
                    hasVideoDeclared = true,
                    format = "mp4",
                    videoCodec = "h264",
                    songDurationMs = firstSong.durationMs.toLong(),
                )
            }
            assertTrue("the synthetic MV should start within ten seconds", playbackStarted.await(10L, TimeUnit.SECONDS))
            fixture.setApiSnapshot(firstSnapshot)
            fixture.publishSnapshot("sync_full", firstSnapshot)
            assertTrue("the playing snapshot should reach the TV activity", awaitActivity(activityScenario) {
                it.findViewById<TextView>(R.id.txtDockNowPlaying)?.text?.toString()?.contains("测试播放片段") == true
            })
            val dockVideoVisible = awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.video_container)?.isShown == true &&
                    it.findViewById<View>(R.id.playerView)?.visibility == View.VISIBLE
            }
            captureScreenshot(activityWindow, context, "dashboard-pip", display)
            activityScenario.onActivity { it.onUserInteraction() }
            assertTrue("the dashboard should remain open before remote search navigation", awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.kioskDashboardView)?.isShown == true
            })
            val dockState = if (dockVideoVisible) "" else readActivity(activityScenario) { activity ->
                val dock = activity.findViewById<View>(R.id.video_container)
                val playerView = activity.findViewById<View>(R.id.playerView)
                val snapshot = privateField<QueueSnapshot?>(activity, "cachedQueueSnapshot")
                val mediaType = snapshot?.playing?.song?.mediaType
                val externalDisplay = privateField<Boolean>(activity, "externalDisplayActive")
                val dockController = privateField<Any>(activity, "bottomDockController")
                val controllerVideoVisible = privateField<Boolean>(dockController, "videoVisible")
                val controllerFullscreen = privateField<Boolean>(dockController, "isFullscreen")
                val kioskController = privateField<Any?>(activity, "kioskController")
                val kioskSnapshot = kioskController?.let { privateField<QueueSnapshot?>(it, "currentSnapshot") }
                "videoContainerVisibility=${dock?.visibility}, videoContainerShown=${dock?.isShown}, " +
                    "playerVisibility=${playerView?.visibility}, playerShown=${playerView?.isShown}, " +
                    "playerParent=${(playerView?.parent as? View)?.let { parent ->
                        runCatching { parent.resources.getResourceEntryName(parent.id) }.getOrDefault("no-id")
                    }}, " +
                    "mediaType=$mediaType, snapshotState=${snapshot?.state}, externalDisplay=$externalDisplay, " +
                    "controllerVideoVisible=$controllerVideoVisible, controllerFullscreen=$controllerFullscreen, " +
                    "kioskSnapshotState=${kioskSnapshot?.state}, kioskHasPlaying=${kioskSnapshot?.playing != null}"
            }
            assertTrue("the bottom dock should show the live PiP player; $dockState", dockVideoVisible)

            // Real remote traversal follows the declared focus graph: Hot ranking -> TikTok -> Song name.
            focusView(activityScenario, R.id.tileHotRanking)
            press(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(R.id.tileTikTok, currentFocusId(activityScenario))
            press(instrumentation, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(R.id.tileSongName, currentFocusId(activityScenario))
            press(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER)
            val pinyinBrowserOpened = awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.browserKeyboardContainer)?.isShown == true &&
                    it.currentFocus?.id == R.id.btnTabSongName
            }
            val browserState = if (pinyinBrowserOpened) "" else readActivity(activityScenario) { activity ->
                val kiosk = privateField<KtvKioskOverlayController>(activity, "kioskController")
                val presentation = privateField<KioskPresentationState>(kiosk, "presentationState")
                val centerTitle = activity.findViewById<TextView>(R.id.txtBrowserCenterTitle)
                val keyboard = activity.findViewById<View>(R.id.browserKeyboardContainer)
                val results = activity.findViewById<View>(R.id.browserResultsRecyclerView)
                val empty = activity.findViewById<View>(R.id.txtBrowserEmptyState)
                "tab=${presentation.currentTab.value} route=${presentation.commercialRoute.value} " +
                    "focus=${activity.currentFocus?.id}:${activity.currentFocus?.javaClass?.simpleName} " +
                    "dashboard=${activity.findViewById<View>(R.id.kioskDashboardView)?.visibility} " +
                    "title=${centerTitle?.visibility}:${centerTitle?.text} keyboard=${keyboard?.visibility} " +
                    "results=${results?.visibility} empty=${empty?.visibility}"
            }
            assertTrue("Song name should open the pinyin browser; $browserState", pinyinBrowserOpened)

            // T9 multi-tap remote digits spell H-K-T-K: 44, 55, 8, 55.
            listOf('4', '4', '5', '5', '8', '5', '5').forEach { digit ->
                press(instrumentation, digitKeyCode(digit))
            }
            assertTrue("the remote digit stream should resolve to HKTK", awaitActivity(activityScenario) {
                it.findViewById<TextView>(R.id.txtKeywordDisplay)?.text?.toString() == "HKTK"
            })
            assertTrue("the final HKTK value should reach the acronym search endpoint", fixture.awaitAcronymQuery("HKTK"))
            assertEquals("the acronym endpoint must receive the complete HKTK value", "HKTK", fixture.lastAcronymQuery)
            assertTrue("the result list should show 海阔天空", awaitActivity(activityScenario, 10_000L) {
                visibleSongTitle(it, "海阔天空") != null
            })
            assertVisibleSongCardFits(activityScenario)
            captureScreenshot(activityWindow, context, "search-results-hktk", display)
            activityScenario.onActivity { it.onUserInteraction() }
            assertNoTextBoundsEscape(activityScenario)

            val songOrderButton = readActivity(activityScenario) { activity ->
                val card = requireNotNull(visibleSongCard(activity, "海阔天空"))
                requireNotNull(card.findViewById<View>(R.id.btnBrowserSongOrder))
            }
            activityScenario.onActivity { assertTrue("the matching song's Order button must accept focus", songOrderButton.requestFocus()) }
            press(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER)

            assertTrue("the order endpoint should receive the selected song", fixture.awaitOrder())
            assertEquals("the song order action must use the order contract", "order", fixture.orderAction)
            assertTrue("the song card should expose the queued state", awaitActivity(activityScenario, 10_000L) {
                visibleOrderButton(it, "海阔天空")?.let { button ->
                    !button.isEnabled &&
                        button.contentDescription?.toString()?.startsWith("已点：") == true &&
                        (button as? TextView)?.text?.toString() == "✓"
                } == true
            })
            assertTrue("the dock queue badge should increment to one", awaitActivity(activityScenario) {
                it.findViewById<TextView>(R.id.txtDockQueueCount)?.text?.toString() == "1"
            })
            assertTrue("the mobile order event should appear in the top marquee", awaitActivity(activityScenario) {
                it.findViewById<TextView>(R.id.txtMarquee)?.text?.toString() ==
                    "来自 手机用户 点播了《海阔天空》"
            })
            assertNoTextBoundsEscape(activityScenario)
            captureScreenshot(activityWindow, context, "ordered-queue-one", display)
            activityScenario.onActivity { it.onUserInteraction() }

            // Back returns through the search page to the dashboard; restore the explicit
            // Song name -> volume-down -> center-PiP D-pad path from the dashboard and dock XML.
            repeat(3) {
                if (isDashboardVisible(activityScenario)) return@repeat
                press(instrumentation, KeyEvent.KEYCODE_BACK)
            }
            assertTrue("Back from pinyin search should restore the dashboard", awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.kioskDashboardView)?.isShown == true
            })
            assertTrue("the last activated Song name tile should regain focus", awaitActivity(activityScenario) {
                it.currentFocus?.id == R.id.tileSongName
            })
            press(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("the Song name Down link should reach volume down", R.id.btnDockVolDown,
                currentFocusId(activityScenario))
            press(instrumentation, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("the dock volume-down Right link should reach the center PiP", R.id.video_container,
                currentFocusId(activityScenario))
            captureScreenshot(activityWindow, context, "dashboard-pip-focused", display)
            activityScenario.onActivity { it.onUserInteraction() }
            // UiAutomation screenshot capture can move window focus to the dock's first item.
            // Restore the already-verified PiP target before sending the fullscreen OK key.
            focusView(activityScenario, R.id.video_container)
            assertEquals("capturing the PiP focus frame must preserve remote focus", R.id.video_container,
                currentFocusId(activityScenario))

            val (mediaIdBeforeFullscreen, positionBeforeFullscreen) = readActivity(activityScenario) {
                // Start the continuity measurement away from the fixture video's loop boundary.
                player.seekTo(0L)
                player.currentMediaItem?.mediaId to engine.currentPositionMs
            }
            press(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER)
            val fullscreenStarted = awaitActivity(activityScenario) { activity ->
                val dock = privateField<KtvBottomDockController>(activity, "bottomDockController")
                val container = activity.findViewById<View>(R.id.sharedPlayerContainer)
                val layout = container?.layoutParams
                dock.isFullscreen &&
                    activity.findViewById<View>(R.id.playerView)?.parent === container &&
                    layout?.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                    layout.height == ViewGroup.LayoutParams.MATCH_PARENT
            }
            val fullscreenState = readActivity(activityScenario) { activity ->
                val dock = privateField<KtvBottomDockController>(activity, "bottomDockController")
                val focus = activity.currentFocus
                "isFullscreen=${dock.isFullscreen}, videoVisible=${privateField<Boolean>(dock, "videoVisible")}, " +
                    "focus=${focus?.id?.let { id ->
                        runCatching { activity.resources.getResourceEntryName(id) }.getOrDefault(id.toString())
                    }}, pipShown=${dock.videoContainer.isShown}, pipClickable=${dock.videoContainer.isClickable}, " +
                    "playerParent=${(activity.findViewById<View>(R.id.playerView)?.parent as? View)?.let { parent ->
                        runCatching { parent.resources.getResourceEntryName(parent.id) }.getOrDefault("no-id")
                    }}, sharedPlayerSize=${activity.findViewById<View>(R.id.sharedPlayerContainer)?.width}x" +
                    "${activity.findViewById<View>(R.id.sharedPlayerContainer)?.height}"
            }
            assertTrue("OK on PiP should move the same PlayerView to fullscreen; $fullscreenState", fullscreenStarted)
            captureScreenshot(activityWindow, context, "mv-fullscreen", display)

            SystemClock.sleep(FULLSCREEN_HOLD_MS)
            press(instrumentation, KeyEvent.KEYCODE_BACK)
            assertTrue("the first Back should hide controls while keeping fullscreen playback active", awaitActivity(activityScenario) { activity ->
                privateField<KtvBottomDockController>(activity, "bottomDockController").isFullscreen &&
                    activity.findViewById<View>(R.id.fullscreenControlsPanel)?.visibility == View.GONE
            })
            press(instrumentation, KeyEvent.KEYCODE_BACK)
            assertTrue("Back should restore the dashboard and the shared dock PiP", awaitActivity(activityScenario) { activity ->
                val dock = privateField<KtvBottomDockController>(activity, "bottomDockController")
                activity.findViewById<View>(R.id.kioskDashboardView)?.isShown == true &&
                    !dock.isFullscreen &&
                    activity.findViewById<View>(R.id.playerView)?.parent === activity.findViewById<View>(R.id.sharedPlayerContainer)
            })
            assertEquals("fullscreen round trip must keep the same media item", mediaIdBeforeFullscreen,
                readActivity(activityScenario) { player.currentMediaItem?.mediaId })
            assertTrue("playback position must continue through fullscreen round trip", readActivity(activityScenario) {
                engine.currentPositionMs >= positionBeforeFullscreen + FULLSCREEN_HOLD_MS - POSITION_TOLERANCE_MS
            })
            assertTrue("playback should continue after returning to PiP", readActivity(activityScenario) { player.isPlaying })
            assertNoSectionOverlap(activityScenario)
            assertNoTextBoundsEscape(activityScenario)
            captureScreenshot(activityWindow, context, "dashboard-pip-restored", display)

            writeDisplayEvidence(context, display, fixtureHost)
        } finally {
            scenario?.close()
            fixture.close()
            config.serverHost = previousHost
            if (previousMode != null && !previousHost.isNullOrBlank()) {
                config.saveMode(previousMode, previousHost)
            }
            config.setModeMigrationVersion(previousMigrationVersion)
            config.forgetServer(SavedServer(fixtureHost, fixtureHost))
        }
    }

    @Test
    fun globalSearchRouteActivationKeepsDpadFocusOnSelectedCommercialTab() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("this acceptance test requires an Android TV AVD", isTelevision(context))

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor(previousHost)
        val previousMigrationVersion = config.getModeMigrationVersion()
        config.serverHost = "127.0.0.1:9"

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activityScenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = activityScenario
            assertTrue("the global search action should be visible", awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.btnTopSearch)?.isShown == true
            })

            focusView(activityScenario, R.id.btnTopSearch)
            press(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue("global search should open the aggregate browser", awaitActivity(activityScenario) {
                it.findViewById<View>(R.id.browserKeyboardContainer)?.isShown == true &&
                    it.currentFocus?.id == R.id.btnTabSongName
            })

            press(instrumentation, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("the next commercial destination should own focus", R.id.btnTabHd,
                currentFocusId(activityScenario))
            activityScenario.onActivity { activity ->
                assertTrue("the focused HD route should handle activation",
                    requireNotNull(activity.findViewById<View>(R.id.btnTabHd)).performClick())
            }
            assertTrue("the HD route should become selected", awaitActivity(activityScenario, 3_000L) {
                it.findViewById<View>(R.id.btnTabHd)?.isSelected == true
            })

            // Let state collectors and posted layout-focus restoration finish before
            // checking the result. The regression loses focus after the selected state
            // has already rendered, so asserting selection alone is insufficient.
            SystemClock.sleep(500L)
            val focusDiagnostic = readActivity(activityScenario) { activity ->
                val focused = activity.currentFocus
                val focusedName = focused?.id?.let { id ->
                    runCatching { activity.resources.getResourceEntryName(id) }.getOrDefault(id.toString())
                }
                "currentFocus=$focusedName, " +
                    "aggregateBrowserShown=${activity.findViewById<View>(R.id.threeColumnBrowserRoot)?.isShown}"
            }
            assertTrue("the aggregate browser should remain visible after route activation", readActivity(activityScenario) {
                it.findViewById<View>(R.id.threeColumnBrowserRoot)?.isShown == true
            })
            assertEquals(
                "confirming the HD route must leave remote focus on its selected capsule; $focusDiagnostic",
                R.id.btnTabHd,
                currentFocusId(activityScenario),
            )

            press(instrumentation, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("right from the selected HD route should focus Singers", awaitActivity(activityScenario) {
                it.currentFocus?.id == R.id.btnTabSingers
            })
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            if (previousMode != null && !previousHost.isNullOrBlank()) {
                config.saveMode(previousMode, previousHost)
            }
            config.setModeMigrationVersion(previousMigrationVersion)
        }
    }

    private fun assertBentoFocusGraph(
        scenario: ActivityScenario<MainActivity>,
        instrumentation: android.app.Instrumentation,
    ) {
        val directions = listOf(
            FocusDirection(KeyEvent.KEYCODE_DPAD_LEFT) { it.nextFocusLeftId },
            FocusDirection(KeyEvent.KEYCODE_DPAD_RIGHT) { it.nextFocusRightId },
            FocusDirection(KeyEvent.KEYCODE_DPAD_UP) { it.nextFocusUpId },
            FocusDirection(KeyEvent.KEYCODE_DPAD_DOWN) { it.nextFocusDownId },
        )
        val tileIds = DASHBOARD_TILE_IDS.toSet()
        val pending = ArrayDeque<Int>().apply { add(R.id.tileHotRanking) }
        val visited = mutableSetOf<Int>()

        while (pending.isNotEmpty()) {
            val sourceId = pending.removeFirst()
            if (!visited.add(sourceId)) continue
            val edges = readActivity(scenario) { activity ->
                val source = requireNotNull(activity.findViewById<View>(sourceId))
                assertTrue("tile $sourceId must be focusable", source.isFocusable)
                directions.map { direction ->
                    val targetId = direction.targetId(source)
                    assertTrue("tile $sourceId must declare every D-pad direction", targetId != View.NO_ID)
                    val target = activity.findViewById<View>(targetId)
                    assertNotNull("tile $sourceId points to missing focus target $targetId", target)
                    assertTrue("focus target $targetId must be focusable", requireNotNull(target).isFocusable)
                    direction to targetId
                }
            }
            for ((direction, targetId) in edges) {
                if (targetId !in tileIds || targetId in visited) continue
                focusView(scenario, sourceId)
                assertEquals("tile $sourceId must own focus before D-pad input", sourceId, currentFocusId(scenario))
                press(instrumentation, direction.keyCode)
                val actualFocusId = currentFocusId(scenario)
                val focusState = if (actualFocusId == targetId) "" else readActivity(scenario) { activity ->
                    val source = activity.findViewById<View>(sourceId)
                    val target = activity.findViewById<View>(targetId)
                    val focused = activity.currentFocus
                    " sourceFocused=${source?.isFocused}, sourceShown=${source?.isShown}, " +
                        "targetShown=${target?.isShown}, targetFocusable=${target?.isFocusable}, " +
                        "targetAttached=${target?.isAttachedToWindow}, targetBounds=${target?.let(::viewBounds)}, " +
                        "actualFocus=${focused?.id?.let { id -> runCatching { activity.resources.getResourceEntryName(id) }.getOrDefault(id.toString()) }}"
                }
                assertEquals(
                    "D-pad transition from $sourceId must reach declared target $targetId.$focusState",
                    targetId,
                    actualFocusId,
                )
                if (targetId !in visited) pending.add(targetId)
            }
        }
        assertEquals("every one of the ten Bento tiles must be reachable by D-pad", tileIds, visited)
    }

    private fun assertVisibleDashboardTextFits(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val dashboard = requireNotNull(activity.findViewById<KtvCommercialDashboardView>(R.id.kioskDashboardView))
            val screen = screenBounds(activity)
            collectTextViews(dashboard).filter { it.isShown && it.text.isNotBlank() }.forEach { text ->
                assertTextInsideScreen(text, screen)
                val tile = generateSequence(text.parent as? View) { it.parent as? View }
                    .firstOrNull { it.id in DASHBOARD_TILE_IDS }
                if (tile != null) {
                    val tileBounds = unscaledViewBounds(tile)
                    for (line in 0 until (text.layout?.lineCount ?: 0)) {
                        val glyphBounds = textLineBounds(text, line)
                        assertTrue(
                            "dashboard text '${text.text}' is clipped inside tile ${activity.resources.getResourceEntryName(tile.id)}: " +
                                "$glyphBounds outside $tileBounds",
                            tileBounds.contains(glyphBounds),
                        )
                    }
                }
            }
        }
    }

    private fun assertVisibleSongCardFits(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val recycler = requireNotNull(activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.browserResultsRecyclerView))
            val songCard = (0 until recycler.childCount)
                .asSequence()
                .map(recycler::getChildAt)
                .firstOrNull { card -> card.findViewById<TextView>(R.id.txtBrowserSongTitle)?.text?.toString() == "海阔天空" }
                ?: throw AssertionError("海阔天空 card must be attached before layout checks")
            val cardBounds = viewBounds(songCard)
            listOf(R.id.txtBrowserSongTitle, R.id.txtBrowserSongMatch, R.id.txtBrowserSongArtist)
                .mapNotNull { id -> songCard.findViewById<TextView>(id) }
                .filter { it.isShown && it.text.isNotBlank() }
                .forEach { text ->
                    for (line in 0 until (text.layout?.lineCount ?: 0)) {
                        val glyphBounds = textLineBounds(text, line)
                        assertTrue(
                            "song card text '${text.text}' is clipped by its card: $glyphBounds outside $cardBounds",
                            cardBounds.contains(glyphBounds),
                        )
                    }
                }

            // The compact card keeps the optional quality chip GONE; validate the visible
            // order/favorite controls instead of requiring a layout from a hidden label.
            listOf(
                R.id.btnBrowserSongOrder,
                R.id.btnBrowserSongOrderTop,
                R.id.btnBrowserSongFavorite,
            ).forEach { id ->
                val action = requireNotNull(songCard.findViewById<View>(id))
                assertTrue("song card action $id should remain visible", action.isShown)
                val actionBounds = viewBounds(action)
                assertTrue("song card action $id should remain inside the card", cardBounds.contains(actionBounds))
            }
        }
    }

    private fun assertHeaderPageDockDoNotOverlap(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val header = requireNotNull(activity.findViewById<View>(R.id.header_bar))
            val page = requireNotNull(activity.findViewById<View>(R.id.page_container))
            val dock = requireNotNull(activity.findViewById<View>(R.id.ktv_bottom_dock))
            val headerRect = viewBounds(header)
            val pageRect = viewBounds(page)
            val dockRect = viewBounds(dock)
            assertTrue("page content must start below the header: $headerRect / $pageRect", pageRect.top >= headerRect.bottom)
            assertTrue("page content must end above the dock: $pageRect / $dockRect", pageRect.bottom <= dockRect.top)
        }
    }

    private fun assertNoSectionOverlap(scenario: ActivityScenario<MainActivity>) =
        assertHeaderPageDockDoNotOverlap(scenario)

    private fun assertNoTextBoundsEscape(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.window.decorView as ViewGroup
            val screen = screenBounds(activity)
            val browserNavigationScroll = activity.findViewById<android.widget.ScrollView>(R.id.browserNavigationScroll)
            if (browserNavigationScroll?.isShown == true) {
                val scrollBounds = viewBounds(browserNavigationScroll)
                assertTrue("browser navigation viewport must remain on screen: $scrollBounds", screen.contains(scrollBounds))
                val viewport = scrollBounds.apply {
                    left += browserNavigationScroll.paddingLeft
                    top += browserNavigationScroll.paddingTop
                    right -= browserNavigationScroll.paddingRight
                    bottom -= browserNavigationScroll.paddingBottom
                }
                listOf(
                    R.id.browserNavSingers,
                    R.id.browserNavLanguages,
                    R.id.browserNavTags,
                    R.id.browserNavRankings,
                    R.id.browserNavSearch,
                    R.id.browserNavFavorites,
                    R.id.browserNavHistory,
                    R.id.browserNavHome,
                ).mapNotNull { activity.findViewById<View>(it) }.filter { it.isShown }.forEach { button ->
                    val buttonText = button as? TextView ?: return@forEach
                    val textBounds = textLineBounds(buttonText)
                    if (Rect.intersects(viewport, textBounds)) {
                        assertTrue(
                            "visible browser navigation text must be fully inside its scroll viewport: " +
                                "${buttonText.text} at $textBounds outside $viewport " +
                                "(scrollY=${browserNavigationScroll.scrollY})",
                            viewport.contains(textBounds),
                        )
                    }
                }
            }
            collectTextViews(root).filter { it.isShown && it.text.isNotBlank() }.forEach { text ->
                val isInScrollableContent = generateSequence(text.parent as? View) { it.parent as? View }
                    .any { it is android.widget.ScrollView || it is androidx.recyclerview.widget.RecyclerView }
                if (isInScrollableContent) return@forEach
                assertTextInsideScreen(text, screen)
                val layout = text.layout ?: return@forEach
                if (text.ellipsize == null) {
                    for (line in 0 until layout.lineCount) {
                        val lineLeft = layout.getLineLeft(line)
                        val lineRight = layout.getLineRight(line)
                        val textId = if (text.id == View.NO_ID) "no-id" else runCatching {
                            text.resources.getResourceEntryName(text.id)
                        }.getOrDefault(text.id.toString())
                        val textHierarchy = generateSequence(text as View?) { it.parent as? View }
                            .take(6)
                            .joinToString(" <- ") { view ->
                                val id = if (view.id == View.NO_ID) "no-id" else runCatching {
                                    view.resources.getResourceEntryName(view.id)
                                }.getOrDefault(view.id.toString())
                                "$id:${view.width}x${view.height}:${view.visibility}:alpha=${view.alpha}"
                            }
                        assertTrue(
                            "text line extends past its measured width: ${text.text}; " +
                                "line=$line bounds=$lineLeft..$lineRight layoutWidth=${layout.width} " +
                                "viewWidth=${text.width} padding=${text.compoundPaddingLeft}/${text.compoundPaddingRight} " +
                                "id=$textId class=${text.javaClass.simpleName} bounds=${viewBounds(text)} " +
                                "scale=${text.scaleX}/${text.scaleY} hierarchy=$textHierarchy",
                            lineLeft >= 0f && lineRight <= layout.width.toFloat(),
                        )
                    }
                }
            }
        }
    }

    private fun assertTextInsideScreen(text: TextView, screen: Rect) {
        val rect = viewBounds(text)
        val parentRect = (text.parent as? View)?.let(::viewBounds)
        val ancestry = generateSequence(text as View?) { it.parent as? View }
            .joinToString(" <- ") { view ->
                val id = if (view.id == View.NO_ID) "no-id" else runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrDefault(view.id.toString())
                "$id:${view.visibility}:${viewBounds(view)}"
            }
        assertTrue(
            "visible text is clipped by the display: '${text.text}' at $rect outside $screen; " +
                "view=${text.width}x${text.height} translationY=${text.translationY} scaleY=${text.scaleY}, " +
                "parent=$parentRect, ancestry=$ancestry",
            screen.contains(rect),
        )
        val layout = text.layout ?: return
        assertTrue("text exceeds the configured line limit: '${text.text}'", text.maxLines <= 0 || layout.lineCount <= text.maxLines)
    }

    private fun captureScreenshot(
        window: Window,
        context: Context,
        name: String,
        display: DisplayProfile,
    ) {
        // The dashboard intentionally runs looping poster/waveform animations, so a global
        // looper-idle barrier can wait indefinitely. Key-driven state is asserted separately;
        // this short settle window lets the corresponding frame reach SurfaceFlinger.
        SystemClock.sleep(SCREENSHOT_SETTLE_MS)
        val bitmap = Bitmap.createBitmap(display.logicalWidth, display.logicalHeight, Bitmap.Config.ARGB_8888)
        val pixelCopyResult = AtomicReference<Int?>(null)
        val pixelCopyFinished = java.util.concurrent.CountDownLatch(1)
        val callbackThread = HandlerThread("ktv-ui-capture-$name").apply { start() }
        try {
            PixelCopy.request(window, bitmap, { result ->
                pixelCopyResult.set(result)
                pixelCopyFinished.countDown()
            }, Handler(callbackThread.looper))
            assertTrue("timed out capturing $name from the activity window", pixelCopyFinished.await(5L, TimeUnit.SECONDS))
            assertEquals("PixelCopy failed for $name", PixelCopy.SUCCESS, pixelCopyResult.get())
        } finally {
            callbackThread.quitSafely()
            callbackThread.join(1_000L)
        }
        assertEquals("$name screenshot must match the Android logical viewport width", display.logicalWidth, bitmap.width)
        assertEquals("$name screenshot must match the Android logical viewport height", display.logicalHeight, bitmap.height)
        val directory = evidenceDirectory(context)
        assertTrue("could not create UI evidence directory ${directory.absolutePath}", directory.exists() || directory.mkdirs())
        val imageFile = File(
            directory,
            "${name}_logical-${bitmap.width}x${bitmap.height}_display-${display.physicalWidth}x${display.physicalHeight}.png",
        )
        FileOutputStream(imageFile).use { output ->
            assertTrue("could not write screenshot ${imageFile.absolutePath}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomeKtvUiEvidence/task7-tv-navigation-$runId")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "unable to publish screenshot evidence ${imageFile.name}"
        }
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output) { "unable to open screenshot evidence ${imageFile.name}" }
            assertTrue("could not export screenshot ${imageFile.name}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

    private fun writeDisplayEvidence(context: Context, display: DisplayProfile, fixtureHost: String) {
        val directory = evidenceDirectory(context)
        assertTrue("could not create UI evidence directory", directory.exists() || directory.mkdirs())
        val properties = listOf(
                "physicalDisplayMode=${display.physicalWidth}x${display.physicalHeight}",
                "logicalViewport=${display.logicalWidth}x${display.logicalHeight}",
                "screenshotResolution=${display.logicalWidth}x${display.logicalHeight}",
                "logicalDensityDpi=${display.densityDpi}",
                "densitySource=Activity resources DisplayMetrics",
                "model=${android.os.Build.MODEL}",
                "android=${android.os.Build.VERSION.RELEASE}",
                "abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
                "fixture=local synthetic HTTP/WebSocket/MV fixture",
                "fixtureHost=$fixtureHost",
            ).joinToString("\n", postfix = "\n")
        File(directory, "display.properties").writeText(properties)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "display-$runId.properties")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/HomeKtvUiEvidence/task7-tv-navigation-$runId")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "unable to publish display evidence"
        }
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output) { "unable to open display evidence" }.writer().use { it.write(properties) }
        }
    }

    private fun evidenceDirectory(context: Context): File =
        File(requireNotNull(context.getExternalFilesDir("ui-evidence")), "task7-tv-navigation-$runId")

    private fun visibleSongTitle(activity: MainActivity, expected: String): TextView? {
        return visibleSongCard(activity, expected)?.findViewById(R.id.txtBrowserSongTitle)
    }

    private fun visibleSongCard(activity: MainActivity, expected: String): View? {
        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.browserResultsRecyclerView)
            ?: return null
        for (index in 0 until recycler.childCount) {
            val card = recycler.getChildAt(index)
            val title = card.findViewById<TextView>(R.id.txtBrowserSongTitle)
            if (title?.text?.toString() == expected) return card
        }
        return null
    }

    private fun visibleOrderButton(activity: MainActivity, expected: String): View? {
        return visibleSongCard(activity, expected)?.findViewById(R.id.btnBrowserSongOrder)
    }

    private fun isDashboardVisible(scenario: ActivityScenario<MainActivity>): Boolean =
        readActivity(scenario) { it.findViewById<View>(R.id.kioskDashboardView)?.isShown == true }

    private fun currentFocusId(scenario: ActivityScenario<MainActivity>): Int =
        readActivity(scenario) { it.currentFocus?.id ?: View.NO_ID }

    private fun focusView(scenario: ActivityScenario<MainActivity>, viewId: Int) {
        scenario.onActivity { activity ->
            val view = requireNotNull(activity.findViewById<View>(viewId))
            val visibilityState = if (view.isShown) "" else {
                val dashboard = activity.findViewById<View>(R.id.kioskDashboardView)
                val kioskRoot = activity.findViewById<View>(R.id.kioskRootOverlay)
                " dashboardVisibility=${dashboard?.visibility}, dashboardShown=${dashboard?.isShown}, " +
                    "kioskRootVisibility=${kioskRoot?.visibility}, kioskActive=${privateField<Boolean>(activity, "fullscreenKioskActive")}, " +
                    "currentFocus=${activity.currentFocus?.id}"
            }
            assertTrue("view $viewId must be shown before focus.$visibilityState", view.isShown)
            assertTrue("view $viewId should accept focus", view.requestFocus())
        }
    }

    private fun press(instrumentation: android.app.Instrumentation, keyCode: Int) {
        val automation = instrumentation.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        assertTrue("Android TV should accept D-pad key down $keyCode", automation.injectInputEvent(down, true))

        val upTime = maxOf(downTime, SystemClock.uptimeMillis())
        val up = KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)
        assertTrue("Android TV should accept D-pad key up $keyCode", automation.injectInputEvent(up, true))
    }

    private fun <T> readActivity(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { activity -> result = block(activity) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 8_000L,
        predicate: (MainActivity) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (readActivity(scenario, predicate)) return true
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun digitKeyCode(digit: Char): Int = when (digit) {
        '0' -> KeyEvent.KEYCODE_0
        '1' -> KeyEvent.KEYCODE_1
        '2' -> KeyEvent.KEYCODE_2
        '3' -> KeyEvent.KEYCODE_3
        '4' -> KeyEvent.KEYCODE_4
        '5' -> KeyEvent.KEYCODE_5
        '6' -> KeyEvent.KEYCODE_6
        '7' -> KeyEvent.KEYCODE_7
        '8' -> KeyEvent.KEYCODE_8
        '9' -> KeyEvent.KEYCODE_9
        else -> error("not a decimal digit: $digit")
    }

    private fun screenBounds(context: Context): Rect {
        val point = Point()
        @Suppress("DEPRECATION")
        (context as MainActivity).windowManager.defaultDisplay.getRealSize(point)
        return Rect(0, 0, point.x, point.y)
    }

    private fun viewBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun unscaledViewBounds(view: View): Rect {
        val bounds = viewBounds(view)
        val insetX = (view.width * (view.scaleX - 1f) / 2f).roundToInt()
        val insetY = (view.height * (view.scaleY - 1f) / 2f).roundToInt()
        val left = bounds.left + insetX
        val top = bounds.top + insetY
        return Rect(
            left,
            top,
            left + view.width,
            top + view.height,
        )
    }

    private fun textLineBounds(text: TextView, line: Int = 0): Rect {
        val view = unscaledViewBounds(text)
        val layout = text.layout ?: return view
        val fontMetrics = text.paint.fontMetricsInt
        val firstBaseline = layout.getLineBaseline(0)
        val baseline = view.top + text.baseline + layout.getLineBaseline(line) - firstBaseline
        val left = view.left + text.compoundPaddingLeft + layout.getLineLeft(line).roundToInt()
        val right = view.left + text.compoundPaddingLeft + layout.getLineRight(line).roundToInt()
        return Rect(left, baseline + fontMetrics.ascent, right, baseline + fontMetrics.descent)
    }

    private fun collectTextViews(root: View): List<TextView> = buildList {
        if (root is TextView) add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(collectTextViews(root.getChildAt(index)))
        }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private fun <T> privateField(owner: Any, name: String): T = owner.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner) as T

    private fun setPrivateField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private data class FocusDirection(val keyCode: Int, val targetId: (View) -> Int)

    private data class DisplayProfile(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val logicalWidth: Int,
        val logicalHeight: Int,
        val densityDpi: Int,
    )

    private class KtvNavigationFixtureServer(private val mediaBytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val apiSnapshot = AtomicReference(QueueSnapshot())
        private val webSocketSnapshot = AtomicReference(QueueSnapshot())
        private val webSockets = CopyOnWriteArrayList<Socket>()
        private val acronymRequests = CopyOnWriteArrayList<String>()
        private val webSocketLatch = java.util.concurrent.CountDownLatch(1)
        private val acronymLatch = java.util.concurrent.CountDownLatch(1)
        private val orderLatch = java.util.concurrent.CountDownLatch(1)
        @Volatile var lastAcronymQuery: String? = null
            private set
        @Volatile var orderAction: String? = null
            private set

        val serverHost: String get() = "127.0.0.1:${server.localPort}"
        val videoUrl: String get() = "http://$serverHost/ktv_synthetic_dual_track.mp4"

        private val song = SongDto(
            id = 72001L,
            title = "海阔天空",
            artist = "Beyond",
            mediaType = "MV",
            durationMs = 30_000,
            titlePinyin = "HAIKUOTIANKONG",
            titleInitials = "HKTK",
            playable = true,
        )

        private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

        private val acceptThread = Thread({ acceptLoop() }, "ktv-task7-fixture-accept").apply {
            isDaemon = true
            start()
        }

        fun start(): KtvNavigationFixtureServer = this

        fun setApiSnapshot(snapshot: QueueSnapshot) {
            apiSnapshot.set(snapshot)
            webSocketSnapshot.set(snapshot)
        }

        fun publishSnapshot(event: String, snapshot: QueueSnapshot) {
            webSocketSnapshot.set(snapshot)
            broadcastSnapshot(event, snapshot)
        }

        fun awaitWebSocket(): Boolean = webSocketLatch.await(8L, TimeUnit.SECONDS)

        fun awaitAcronymSearch(): Boolean = acronymLatch.await(10L, TimeUnit.SECONDS)

        fun awaitAcronymQuery(expected: String, timeoutMs: Long = 10_000L): Boolean {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (lastAcronymQuery == expected) return true
                SystemClock.sleep(25L)
            }
            return lastAcronymQuery == expected
        }

        fun awaitOrder(): Boolean = orderLatch.await(10L, TimeUnit.SECONDS)

        private fun acceptLoop() {
            while (!closed.get()) {
                try {
                    val client = server.accept()
                    Thread({ serve(client) }, "ktv-task7-fixture-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!closed.get()) SystemClock.sleep(20L)
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 1_000
                val input = client.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }

                if (headers["upgrade"].equals("websocket", ignoreCase = true)) {
                    serveWebSocket(client, input, headers)
                } else {
                    serveHttp(client, input, requestLine, headers)
                }
            } catch (_: Exception) {
                // Client cancellation while navigating or closing the test is expected.
            } finally {
                webSockets.remove(client)
                runCatching { client.close() }
            }
        }

        private fun serveHttp(client: Socket, input: java.io.InputStream, requestLine: String, headers: Map<String, String>) {
            val requestTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val bodyLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_REQUEST_BODY) ?: 0
            val requestBody = ByteArray(bodyLength)
            var offset = 0
            while (offset < requestBody.size) {
                val read = input.read(requestBody, offset, requestBody.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (requestTarget == "/ktv_synthetic_dual_track.mp4" ||
                requestTarget.startsWith("/ktv_synthetic_dual_track.mp4?")) {
                serveVideo(client, headers)
                return
            }
            val response = when {
                requestTarget.startsWith("/api/songs?acronym=", ignoreCase = true) -> {
                    lastAcronymQuery = requestTarget.substringAfter("acronym=").substringBefore('&')
                    acronymRequests += lastAcronymQuery.orEmpty()
                    acronymLatch.countDown()
                    HttpResponse(200, json.encodeToString(ListSerializer(SongDto.serializer()), listOf(song)))
                }
                requestTarget == "/api/queue" -> HttpResponse(200, json.encodeToString(QueueSnapshot.serializer(), apiSnapshot.get()))
                requestTarget == "/api/user" -> HttpResponse(
                    200,
                    json.encodeToString(UserProfile.serializer(), UserProfile(id = 17L, nickname = "仪器测试")),
                )
                requestTarget == "/api/room/host" -> HttpResponse(200, "{\"claimed\":false,\"isHost\":false}")
                requestTarget.startsWith("/api/browse/artists/initials") -> HttpResponse(200, "[]")
                requestTarget == "/api/control" -> handleControl(requestBody)
                requestTarget.startsWith("/api/") -> HttpResponse(200, "{}")
                else -> HttpResponse(404, "{}")
            }
            writeHttp(client, response)
        }

        private fun handleControl(requestBody: ByteArray): HttpResponse {
            val body = requestBody.toString(StandardCharsets.UTF_8)
            val action = runCatching {
                Json.parseToJsonElement(body).jsonObject["action"]?.jsonPrimitive?.content
            }.getOrNull()
            orderAction = action
            if (action == "order") {
                val current = apiSnapshot.get()
                val ordered = current.copy(
                    list = current.list + QueueEntry(
                        queueId = 502L,
                        song = song,
                        orderedByNick = "手机用户",
                        status = "waiting",
                    ),
                    state = "playing",
                    stateRevision = current.stateRevision + 1,
                )
                apiSnapshot.set(ordered)
                webSocketSnapshot.set(ordered)
                broadcastSnapshot("queue_updated", ordered)
                orderLatch.countDown()
                return HttpResponse(200, json.encodeToString(QueueSnapshot.serializer(), ordered))
            }
            return HttpResponse(200, json.encodeToString(QueueSnapshot.serializer(), apiSnapshot.get()))
        }

        private fun serveVideo(client: Socket, headers: Map<String, String>) {
            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            val start = range?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, mediaBytes.size - 1) ?: 0
            val requestedEnd = range?.groupValues?.get(2)?.toIntOrNull() ?: mediaBytes.lastIndex
            val end = requestedEnd.coerceIn(start, mediaBytes.lastIndex)
            val length = end - start + 1
            val output = BufferedOutputStream(client.getOutputStream())
            val status = if (range == null) "200 OK" else "206 Partial Content"
            output.write("HTTP/1.1 $status\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            if (range != null) output.write("Content-Range: bytes $start-$end/${mediaBytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(mediaBytes, start, length)
            output.flush()
        }

        private fun serveWebSocket(client: Socket, input: java.io.InputStream, headers: Map<String, String>) {
            val key = headers["sec-websocket-key"] ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.ISO_8859_1)),
            )
            val output = BufferedOutputStream(client.getOutputStream())
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
            )
            output.flush()
            webSockets += client
            webSocketLatch.countDown()
            writeWebSocketText(
                output,
                """{"type":"player_role","payload":{"role":"ACTIVE","generation":1,"lease_ms":300000}}""",
            )
            writeWebSocketText(output, snapshotEvent("sync_full", webSocketSnapshot.get()))

            while (!closed.get() && !client.isClosed) {
                val opcode = readWebSocketFrame(input, output) ?: continue
                if (opcode == CLOSE_OPCODE) break
            }
        }

        private fun broadcastSnapshot(event: String, snapshot: QueueSnapshot) {
            val text = snapshotEvent(event, snapshot)
            webSockets.toList().forEach { client ->
                runCatching { writeWebSocketText(BufferedOutputStream(client.getOutputStream()), text) }
                    .onFailure { webSockets.remove(client) }
            }
        }

        private fun snapshotEvent(event: String, snapshot: QueueSnapshot): String =
            "{\"type\":\"$event\",\"payload\":${json.encodeToString(QueueSnapshot.serializer(), snapshot)}}"

        private fun readWebSocketFrame(input: java.io.InputStream, output: BufferedOutputStream): Int? {
            val first = try {
                input.read()
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
            if (first < 0) return CLOSE_OPCODE
            val second = input.read()
            if (second < 0) return CLOSE_OPCODE
            var length = second and 0x7F
            if (length == 126) length = (input.read() shl 8) or input.read()
            else if (length == 127) {
                repeat(4) { input.read() }
                length = ((input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read())
            }
            val mask = if ((second and 0x80) != 0) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.coerceIn(0, MAX_WEBSOCKET_FRAME))
            readFully(input, payload)
            if (mask != null) payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
            val opcode = first and 0x0F
            if (opcode == PING_OPCODE) writeWebSocketFrame(output, PONG_OPCODE, payload)
            return opcode
        }

        private fun readFully(input: java.io.InputStream, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw java.io.EOFException()
                offset += read
            }
        }

        private fun writeWebSocketText(output: BufferedOutputStream, text: String) =
            writeWebSocketFrame(output, TEXT_OPCODE, text.toByteArray(StandardCharsets.UTF_8))

        private fun writeWebSocketFrame(output: BufferedOutputStream, opcode: Int, payload: ByteArray) {
            synchronized(output) {
                output.write(0x80 or opcode)
                when {
                    payload.size < 126 -> output.write(payload.size)
                    payload.size <= 0xFFFF -> {
                        output.write(126)
                        output.write((payload.size ushr 8) and 0xFF)
                        output.write(payload.size and 0xFF)
                    }
                    else -> throw IllegalArgumentException("fixture websocket frame too large")
                }
                output.write(payload)
                output.flush()
            }
        }

        private fun readLine(input: java.io.InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
                if (bytes.size() > MAX_HEADER_LINE) throw IllegalArgumentException("fixture header line too long")
            }
            return bytes.toString("ISO-8859-1")
        }

        private fun writeHttp(client: Socket, response: HttpResponse) {
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val output = BufferedOutputStream(client.getOutputStream())
            output.write("HTTP/1.1 ${response.status} ${if (response.status == 200) "OK" else "Not Found"}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            webSockets.toList().forEach { runCatching { it.close() } }
            runCatching { server.close() }
            acceptThread.join(1_000L)
        }

        private data class HttpResponse(val status: Int, val body: String)

        companion object {
            private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            private const val TEXT_OPCODE = 0x1
            private const val CLOSE_OPCODE = 0x8
            private const val PING_OPCODE = 0x9
            private const val PONG_OPCODE = 0xA
            private const val MAX_HEADER_LINE = 16_384
            private const val MAX_REQUEST_BODY = 64 * 1024
            private const val MAX_WEBSOCKET_FRAME = 128 * 1024
        }
    }

    companion object {
        private const val FULLSCREEN_HOLD_MS = 1_000L
        private const val POSITION_TOLERANCE_MS = 200L
        private const val SCREENSHOT_SETTLE_MS = 100L
        private val SUPPORTED_TV_RESOLUTIONS = setOf(1280 to 720, 1920 to 1080, 3840 to 2160)
        private val DASHBOARD_TILE_IDS = listOf(
            R.id.tileHotRanking,
            R.id.tileNewSongs,
            R.id.tileSinger,
            R.id.tileSongName,
            R.id.tileLanguage,
            R.id.tileTheme,
            R.id.tileOpera,
            R.id.tileTikTok,
            R.id.tileFavorites,
            R.id.tileLocal,
        )
    }
}
