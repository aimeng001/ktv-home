package com.homektv.tv.ui

import android.content.ContentValues
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.lifecycleScope
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceModeMigrationPolicy
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures real rendered Android views on isolated AVDs; no server or song fixtures are used. */
@RunWith(AndroidJUnit4::class)
class UiSimulationScreenshotInstrumentedTest {
    private val runId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    @Test
    fun capturesConnectionAndPhoneNavigationStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val setup = ActivityScenario.launch<SetupActivity>(
            android.content.Intent(instrumentation.targetContext, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_FORCE_SETUP, true),
        )
        try {
            instrumentation.waitForIdleSync()
            setup.onActivity { activity ->
                assertTrue(
                    "connection and setup pages must render the selected stage artwork",
                    KtvDashboardBackground.isUsingSelectedArtwork(activity.findViewById(R.id.setupScroll)),
                )
            }
            capture("02_server_discovery_actual.png")
            setup.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnOpenManualSetup).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setupManualPage).visibility)
            }
            instrumentation.waitForIdleSync()
            capture("03_manual_server_entry_actual.png")
            setup.onActivity { activity ->
                val formScroll = activity.findViewById<android.widget.ScrollView>(R.id.setupFormScroll)
                val contentHeight = formScroll.getChildAt(0).height
                formScroll.post { formScroll.scrollTo(0, contentHeight) }
            }
            instrumentation.waitForIdleSync()
            setup.onActivity { activity ->
                val formScroll = activity.findViewById<android.widget.ScrollView>(R.id.setupFormScroll)
                assertFalse(
                    "manual setup form must be at its bottom after scrolling or fit on screen",
                    formScroll.canScrollVertically(1),
                )
                assertTrue("device mode selector must be reachable at the bottom of manual setup", activity.findViewById<View>(R.id.modeGroup).isShown)
                assertTrue("connect action must be reachable at the bottom of manual setup", activity.findViewById<View>(R.id.btnConnect).isShown)
            }
            instrumentation.waitForIdleSync()
            capture("03_manual_server_entry_bottom_actual.png")
        } finally {
            setup.close()
        }

        val controller = ActivityScenario.launch<ControllerActivity>(
            android.content.Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            val navLabels = listOf("目录", "搜索", "队列", "遥控", "我的")
            val screenshotNames = listOf(
                "29_phone_catalog_actual.png",
                "30_phone_search_actual.png",
                "31_phone_queue_actual.png",
                "32_phone_remote_actual.png",
                "33_phone_my_actual.png",
            )
            var phoneNavigationVisible = false
            controller.onActivity { activity ->
                assertTrue(
                    "phone controller pages must render the selected stage artwork",
                    KtvDashboardBackground.isUsingSelectedArtwork(activity.findViewById(R.id.controllerRoot)),
                )
                val navigation = activity.findViewById<LinearLayout>(R.id.phoneNav)
                phoneNavigationVisible = navigation.visibility == View.VISIBLE
                if (phoneNavigationVisible) assertEquals(navLabels.size, navigation.childCount)
            }
            if (phoneNavigationVisible) {
                navLabels.indices.forEach { index ->
                    controller.onActivity { activity ->
                        val navigation = activity.findViewById<LinearLayout>(R.id.phoneNav)
                        val tab = navigation.getChildAt(index) as Button
                        assertEquals(navLabels[index], tab.text.toString())
                        assertTrue(tab.performClick())
                    }
                    instrumentation.waitForIdleSync()
                    controller.onActivity { activity ->
                        val tab = activity.findViewById<LinearLayout>(R.id.phoneNav).getChildAt(index)
                        assertTrue("phone screenshot ${navLabels[index]} must focus its selected tab", tab.requestFocusFromTouch())
                        assertTrue("phone screenshot ${navLabels[index]} must show its focused tab", tab.hasFocus())
                    }
                    capture(screenshotNames[index])
                }
            } else {
                instrumentation.waitForIdleSync()
                capture("tv_controller_all_panels_actual.png")
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun capturesDashboardComponentAndEmptyQueueDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            android.content.Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        var dialog: KtvQueueDrawerDialog? = null
        try {
            scenario.onActivity { activity ->
                val dashboard = KtvCommercialDashboardView(activity).apply {
                    onTileSelected = {}
                }
                activity.setContentView(dashboard)
                dashboard.requestDashboardFocus()
            }
            instrumentation.waitForIdleSync()
            capture("07_dashboard_component_actual.png")

            scenario.onActivity { activity ->
                dialog = KtvQueueDrawerDialog(
                    context = activity,
                    currentUserId = null,
                    isHost = false,
                    coordinator = null,
                    onTop = {},
                    onCancel = {},
                    onShuffle = {},
                ).apply {
                    show()
                    submitQueue("当前暂无歌曲播放", emptyList())
                }
                val empty = requireNotNull(dialog?.window?.decorView)
                    .findViewById<View>(R.id.txtDrawerEmpty)
                assertEquals(View.VISIBLE, empty.visibility)
            }
            instrumentation.waitForIdleSync()
            capture("19_queue_empty_actual.png")
        } finally {
            scenario.onActivity { dialog?.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun capturesFullTvDashboardAndExercisesDpadFocusOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("full dashboard and D-pad focus are TV-only", isTelevision)

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                android.content.Intent(context, MainActivity::class.java),
            )
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(400L)

            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertEquals(View.VISIBLE, overlayBinding.kioskRootOverlay.visibility)
                assertTrue(KtvDashboardBackground.isUsingSelectedArtwork(overlayBinding.kioskRootOverlay))
                assertTrue(
                    "standby must share the selected stage artwork",
                    KtvDashboardBackground.isUsingSelectedArtwork(activity.findViewById(R.id.standbyPanel)),
                )
                assertTrue(
                    "the legacy queue surface must share the selected stage artwork",
                    KtvDashboardBackground.isUsingSelectedArtwork(activity.findViewById(R.id.queueOverlay)),
                )
                assertTrue(overlayBinding.kioskDashboardView.binding.tileHotRanking.hasFocus())
                assertEquals("分类", overlayBinding.tabCategories.text.toString())
                assertEquals("语种", overlayBinding.tabLanguages.text.toString())
                val density = activity.resources.displayMetrics.density
                val minimumTileGapPx = (10 * density).toInt()
                val tileGapPx = overlayBinding.kioskDashboardView.binding.tileSinger.left -
                    overlayBinding.kioskDashboardView.binding.tileHotRanking.right
                assertTrue(
                    "reference dashboard cards need visible horizontal gutters; actual=$tileGapPx px minimum=$minimumTileGapPx px",
                    tileGapPx >= minimumTileGapPx,
                )
                val avatarFixture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                assertTrue(overlayBinding.kioskBottomBar.binding.imgNowPlayingDisc.clipToOutline)
                overlayBinding.kioskBottomBar.setArtistAvatar(avatarFixture)
                assertSame(avatarFixture, (overlayBinding.kioskBottomBar.binding.imgNowPlayingDisc.drawable as BitmapDrawable).bitmap)
                overlayBinding.kioskBottomBar.setArtistAvatar(null)
                assertTrue(overlayBinding.kioskBottomBar.binding.imgNowPlayingDisc.drawable !is BitmapDrawable)
                avatarFixture.recycle()
            }
            android.os.SystemClock.sleep(2_500L)
            capture("07_dashboard_idle_layout_actual.png")

            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                // Layout-only fixture: show the real 16:9 PlayerView frame, but never invent song/video data.
                overlayBinding.txtPipLabel.text = ""
                overlayBinding.pipVideoFrame.visibility = View.VISIBLE
            }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(4_000L)
            capture("07_dashboard_pip_layout_fixture.png")

            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                val controller = MainActivity::class.java
                    .getDeclaredField("kioskController")
                    .apply { isAccessible = true }
                    .get(activity) as KtvKioskOverlayController
                // Isolated visual fixture only: render a labeled playing state without starting a player or contacting Docker.
                controller.updateSnapshot(
                    QueueSnapshot(
                        playing = NowPlaying(song = SongDto(id = -1, title = "播放布局夹具", artist = "测试")),
                        state = "playing",
                    ),
                )
                controller.openQueueDrawer()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                val controller = MainActivity::class.java
                    .getDeclaredField("kioskController")
                    .apply { isAccessible = true }
                    .get(activity) as KtvKioskOverlayController
                val dialog = KtvKioskOverlayController::class.java
                    .getDeclaredField("queueDialog")
                    .apply { isAccessible = true }
                    .get(controller) as KtvQueueDrawerDialog
                val drawerPanel = requireNotNull(dialog.window?.decorView?.findViewById<View>(R.id.queueDrawerPanel))
                val pipRect = android.graphics.Rect()
                val drawerRect = android.graphics.Rect()
                assertTrue("layout-only PiP must remain visible beside the drawer", overlayBinding.pipVideoFrame.getGlobalVisibleRect(pipRect))
                assertTrue("queue drawer must be visible for the overlap check", drawerPanel.getGlobalVisibleRect(drawerRect))
                assertFalse(
                    "the queue drawer must not cover any part of the PiP frame: pip=$pipRect drawer=$drawerRect",
                    android.graphics.Rect.intersects(pipRect, drawerRect),
                )
            }
            instrumentation.waitForIdleSync()
            capture("queue_drawer_pip_visible_area_layout_fixture.png")

            scenario.onActivity { activity ->
                val controller = MainActivity::class.java
                    .getDeclaredField("kioskController")
                    .apply { isAccessible = true }
                    .get(activity) as KtvKioskOverlayController
                val dialog = KtvKioskOverlayController::class.java
                    .getDeclaredField("queueDialog")
                    .apply { isAccessible = true }
                    .get(controller) as KtvQueueDrawerDialog
                dialog.dismiss()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                val layoutParams = overlayBinding.pipVideoFrame.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                assertEquals("closing the drawer restores the centered PiP", 0.5f, layoutParams.horizontalBias, 0.001f)
            }

            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("DPAD_RIGHT should move focus from Hot ranking to Singer", overlayBinding.kioskDashboardView.binding.tileSinger.hasFocus())
            }
            capture("07_dashboard_dpad_right_layout_fixture.png")

            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("DPAD_DOWN should follow the Bento focus graph to Song name", overlayBinding.kioskDashboardView.binding.tileSongName.hasFocus())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("focus must continue from Song name into the bottom-center PiP", activity.findViewById<View>(R.id.video_container).hasFocus())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            scenario.onActivity { activity ->
                assertTrue("footer focus must reach the queue action", activity.findViewById<View>(R.id.btnDockQueue).hasFocus())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("legacy footer actions must remain reachable after the four primary actions", overlayBinding.kioskBottomBar.binding.btnRestart.hasFocus())
                assertTrue(overlayBinding.kioskBottomBar.binding.footerActionScroll.scrollX > 0)
            }
            capture("07_dashboard_footer_focus_actual.png")

            scenario.onActivity { activity ->
                val kioskOverlay = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                kioskOverlay.kioskRootOverlay.visibility = View.GONE
                MainActivity::class.java.getDeclaredMethod("ensureAudioOverlayBinding")
                    .apply { isAccessible = true }
                    .invoke(activity)
                val audioOverlay = activity.findViewById<View>(R.id.audioOverlay)
                assertTrue(
                    "the audio playback surface must render the selected stage artwork",
                    KtvDashboardBackground.isUsingSelectedArtwork(audioOverlay),
                )
                audioOverlay.visibility = View.VISIBLE
            }
            instrumentation.waitForIdleSync()
            capture("audio_playback_surface_empty_fixture.png")
        } finally {
            scenario?.close()
            config.saveMode(previousMode, previousHost)
        }
    }

    @Test
    fun capturesEveryVisibleTvCatalogAndPersonalTabOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("TV catalog and personal tabs require a TV layout", isTelevision)

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(400L)

            val pages = listOf(
                "08_search_empty_actual.png",
                "10_singer_catalog_offline_actual.png",
                "12_language_catalog_offline_actual.png",
                "14_category_catalog_offline_actual.png",
                "16_favorites_offline_actual.png",
                "17_history_offline_actual.png",
            )
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertEquals(View.VISIBLE, overlayBinding.kioskRootOverlay.visibility)
                assertTrue(KtvDashboardBackground.isUsingSelectedArtwork(overlayBinding.kioskRootOverlay))
                val tabs = listOf(
                    overlayBinding.tabPinyin,
                    overlayBinding.tabSingers,
                    overlayBinding.tabLanguages,
                    overlayBinding.tabCategories,
                    overlayBinding.tabFavorites,
                    overlayBinding.tabHistory,
                )
                assertEquals(pages.size, tabs.size)
            }

            pages.forEachIndexed { index, fileName ->
                scenario.onActivity { activity ->
                    val overlayBinding = MainActivity::class.java
                        .getDeclaredField("kioskOverlayBinding")
                        .apply { isAccessible = true }
                        .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                    val tab = when (index) {
                        0 -> overlayBinding.tabPinyin
                        1 -> overlayBinding.tabSingers
                        2 -> overlayBinding.tabLanguages
                        3 -> overlayBinding.tabCategories
                        4 -> overlayBinding.tabFavorites
                        else -> overlayBinding.tabHistory
                    }
                    assertTrue("TV page tab $index must remain clickable", tab.performClick())
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val overlayBinding = MainActivity::class.java
                        .getDeclaredField("kioskOverlayBinding")
                        .apply { isAccessible = true }
                        .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                    val tab = when (index) {
                        0 -> overlayBinding.tabPinyin
                        1 -> overlayBinding.tabSingers
                        2 -> overlayBinding.tabLanguages
                        3 -> overlayBinding.tabCategories
                        4 -> overlayBinding.tabFavorites
                        else -> overlayBinding.tabHistory
                    }
                    assertTrue("TV screenshot $index must focus its selected tab", tab.requestFocusFromTouch())
                    assertTrue("TV screenshot $index must show its focused page tab", tab.hasFocus())
                }
                android.os.SystemClock.sleep(350L)
                capture(fileName)
            }
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
        }
    }

    @Test
    fun focusedSelectedKioskTabsKeepReadableTextOnTheGoldFocusSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTelevision) return

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val overlay = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertEquals(View.VISIBLE, overlay.kioskRootOverlay.visibility)

                listOf(
                    "搜索" to overlay.tabPinyin,
                    "语种" to overlay.tabLanguages,
                ).forEach { (label, tab) ->
                    assertTrue("$label navigation action must remain available", tab.performClick())
                    assertTrue("$label tab must retain selected state", tab.isSelected)
                    assertTrue("$label tab must be focusable", tab.requestFocusFromTouch())
                    assertTrue("$label tab must retain visible focus", tab.hasFocus())
                    val backgroundColor = focusedSolidColor(tab.background)
                    val contrast = contrastRatio(tab.currentTextColor, backgroundColor)
                    assertTrue(
                        "$label focused label contrast was ${"%.2f".format(Locale.US, contrast)}:1; " +
                            "foreground=#${tab.currentTextColor.toUInt().toString(16)} " +
                            "background=#${backgroundColor.toUInt().toString(16)}",
                        contrast >= 4.5,
                    )
                }
            }
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
        }
    }

    @Test
    fun focusedVocalChoiceKeepsReadableTextOnGoldFocusSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("vocal focus contrast is a TV-only behavior", isTelevision)

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val previousMigrationVersion = config.getModeMigrationVersion()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.PLAYER, localOfflineHost)
        // Keep the first-run migration prompt from covering the focused vocal choices.
        config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)
        assertEquals("vocal panel fixture must run in PLAYER mode", DeviceMode.PLAYER, config.effectiveMode(localOfflineHost))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val keyDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                val keyUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue("PLAYER-mode DPAD_CENTER must be handled by MainActivity", activity.dispatchKeyEvent(keyDown))
                activity.dispatchKeyEvent(keyUp)
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.vocalPanel).visibility)
                listOf(
                    "原唱" to activity.findViewById<Button>(R.id.btnVocalOriginal),
                    "伴唱" to activity.findViewById<Button>(R.id.btnVocalAccompaniment),
                ).forEach { (label, button) ->
                    assertTrue("$label choice must accept TV focus", button.requestFocus())
                    assertTrue("$label choice must expose its focused state", button.hasFocus())
                    val backgroundColor = focusedSolidColor(button.background)
                    val contrast = contrastRatio(button.currentTextColor, backgroundColor)
                    assertTrue(
                        "$label focused label contrast was ${"%.2f".format(Locale.US, contrast)}:1; " +
                            "foreground=#${button.currentTextColor.toUInt().toString(16)} " +
                            "background=#${backgroundColor.toUInt().toString(16)}",
                        contrast >= 4.5,
                    )
                }
                activity.findViewById<Button>(R.id.btnVocalAccompaniment).requestFocus()
            }
            capture("tv_vocal_accompaniment_focused_actual.png")
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
            config.setModeMigrationVersion(previousMigrationVersion)
        }
    }

    @Test
    fun tvOverlaysPreserveDesignedPaddingWhenSystemInsetsAreApplied() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("overlay insets are validated on a TV layout", isTelevision)

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val previousMigrationVersion = config.getModeMigrationVersion()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.PLAYER, localOfflineHost)
        // Keep the first-run migration prompt from covering the remote-menu screenshot.
        config.setModeMigrationVersion(DeviceModeMigrationPolicy.CURRENT_VERSION)
        assertEquals("remote menu fixture must run in PLAYER mode", DeviceMode.PLAYER, config.effectiveMode(localOfflineHost))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val keyDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MENU)
                val keyUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MENU)
                assertTrue("PLAYER-mode MENU must be handled by MainActivity", activity.dispatchKeyEvent(keyDown))
                activity.dispatchKeyEvent(keyUp)
                val remoteMenu = activity.findViewById<View>(R.id.remoteMenu)
                assertEquals(View.VISIBLE, remoteMenu.visibility)
                val expectedMenuPadding = (18 * context.resources.displayMetrics.density).toInt()
                assertTrue(
                    "remote menu must retain its designed 18dp side inset after system insets",
                    remoteMenu.paddingLeft >= expectedMenuPadding,
                )
                assertTrue(activity.findViewById<Button>(R.id.remoteQueue).isShown)
            }
            capture("tv_remote_menu_insets_actual.png")
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(R.id.remoteQueue).performClick())

                val queueOverlay = activity.findViewById<View>(R.id.queueOverlay)
                assertEquals(View.VISIBLE, queueOverlay.visibility)
                val expectedQueuePadding = (44 * context.resources.displayMetrics.density).toInt()
                assertTrue(
                    "queue title and controls must retain the designed 44dp inset after system insets",
                    queueOverlay.paddingLeft >= expectedQueuePadding,
                )
            }
            capture("tv_queue_insets_actual.png")
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
            config.setModeMigrationVersion(previousMigrationVersion)
        }
    }

    @Test
    fun transientFeedbackUsesSafeStageAndLeavesPipAndPlaybackControlsAccessible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTelevision) return

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)
        var scenario: ActivityScenario<MainActivity>? = null
        var host: KioskTransientFeedbackHost? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val overlay = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertEquals(View.VISIBLE, overlay.kioskRootOverlay.visibility)
                assertTrue(overlay.kioskDashboardView.binding.tileSongName.requestFocus())

                // Layout-only fixture: expose the real PiP frame without attaching a player or inventing a song.
                overlay.txtPipLabel.text = ""
                overlay.pipVideoFrame.visibility = View.VISIBLE
                host = KioskTransientFeedbackHost(overlay.kioskTransientFeedback, activity.lifecycleScope)
                host?.show("网络连接暂时失败，请检查点歌服务")
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val overlay = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                val banner = overlay.kioskTransientFeedback
                assertEquals(View.VISIBLE, banner.visibility)
                assertFalse(banner.isFocusable)
                assertFalse(banner.isClickable)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, banner.accessibilityLiveRegion)
                assertTrue(overlay.kioskDashboardView.binding.tileSongName.hasFocus())

                fun visibleRect(view: View) = android.graphics.Rect().also {
                    assertTrue("${view.resources.getResourceEntryName(view.id)} must be visible", view.getGlobalVisibleRect(it))
                }
                val bannerRect = visibleRect(banner)
                val contentRect = visibleRect(overlay.kioskMainContent)
                val pipRect = visibleRect(overlay.pipVideoFrame)
                val footerRect = visibleRect(overlay.kioskBottomBar)
                assertFalse("the PiP fixture must have measurable bounds", pipRect.isEmpty)
                assertTrue("banner must reserve vertical space before content", bannerRect.bottom <= contentRect.top)
                assertFalse("banner must not cover the PiP frame", android.graphics.Rect.intersects(bannerRect, pipRect))
                assertFalse("banner must not cover the playback bar", android.graphics.Rect.intersects(bannerRect, footerRect))
                assertTrue(overlay.kioskBottomBar.binding.btnPlayPause.isShown)
                assertTrue(overlay.kioskBottomBar.binding.btnNext.isShown)
                assertTrue(overlay.kioskBottomBar.binding.btnVocalToggle.isShown)
                assertTrue(overlay.kioskBottomBar.binding.btnQueueBadge.isShown)
                // Re-apply the layout-only PiP fixture after the controller's state render, immediately before capture.
                overlay.pipVideoFrame.visibility = View.VISIBLE
            }
            capture("kiosk_transient_feedback_safe_area_actual.png", delayMillis = 0L)
            holdForEvidenceExport()

            scenario.onActivity { host?.show("短暂提示应在 2.5 秒后自动消失") }
            android.os.SystemClock.sleep(2_700L)
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<View>(R.id.kioskTransientFeedback).visibility)
            }

            scenario.onActivity {
                host?.clear()
                assertEquals(View.GONE, it.findViewById<View>(R.id.kioskTransientFeedback).visibility)
            }
        } finally {
            scenario?.onActivity { host?.clear() }
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
        }
    }

    @Test
    fun phoneHeaderKeepsTitleAndStatusOnOneRowWithActionsBelow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val isPhone = !isTelevision && context.resources.configuration.smallestScreenWidthDp < 600
        if (!isPhone) return

        val scenario = ActivityScenario.launch<ControllerActivity>(
            android.content.Intent(context, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.brandTitle)
                val status = activity.findViewById<android.widget.TextView>(R.id.statusText)
                val actions = activity.findViewById<LinearLayout>(R.id.headerActions)
                val actionsRow = activity.findViewById<LinearLayout>(R.id.headerActionsRow)
                val subtitle = activity.findViewById<View>(R.id.controllerSubtitle)
                assertTrue(title.isShown)
                assertEquals(1, title.lineCount)
                assertEquals(View.VISIBLE, actionsRow.visibility)
                assertEquals(3, actions.childCount)
                assertEquals(R.id.headerActionsRow, (actions.parent as View).id)

                fun rect(view: View) = android.graphics.Rect().also {
                    assertTrue("${view.resources.getResourceEntryName(view.id)} must be visible", view.getGlobalVisibleRect(it))
                }
                val titleRect = rect(title)
                val statusRect = rect(status)
                val rowRect = rect(actionsRow)
                val actionsRect = rect(actions)
                val subtitleRect = rect(subtitle)
                val contentRect = rect(activity.findViewById(R.id.controllerContent))
                assertFalse("title and connection status must not overlap", android.graphics.Rect.intersects(titleRect, statusRect))
                assertTrue("action buttons must stay within their phone row", rowRect.contains(actionsRect))
                assertTrue("phone action row must stay within content width", contentRect.contains(rowRect))
                assertTrue("subtitle must follow the action row", rowRect.bottom <= subtitleRect.top)
            }
            capture("phone_header_actions_row_actual.png")
            holdForEvidenceExport()
        } finally {
            scenario.close()
        }
    }

    private fun focusedSolidColor(drawable: Drawable): Int = when (drawable) {
        is StateListDrawable -> focusedSolidColor(drawable.current)
        is GradientDrawable -> requireNotNull(drawable.color).defaultColor
        else -> error("Expected a focused solid-color navigation surface, got ${drawable.javaClass.name}")
    }

    private fun contrastRatio(foreground: Int, background: Int): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(android.graphics.Color.red(color)) +
            0.7152 * channel(android.graphics.Color.green(color)) +
            0.0722 * channel(android.graphics.Color.blue(color))
    }

    private fun capture(fileName: String, delayMillis: Long = 200L) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (delayMillis > 0L) android.os.SystemClock.sleep(delayMillis)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "AVD screenshot capture failed for $fileName"
        }
        val context = instrumentation.targetContext
        val base = requireNotNull(context.getExternalFilesDir("ui-evidence"))
        val directory = File(base, runId).apply { check(mkdirs() || isDirectory) }
        val output = File(directory, fileName)
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        exportScreenshotForReviewIfRequested(context, bitmap, fileName)
        bitmap.recycle()
        val metrics = context.resources.displayMetrics
        File(directory, "manifest.tsv").appendText(
            "$fileName\t${metrics.widthPixels}x${metrics.heightPixels}\tdensity=${metrics.density}\n",
        )
        Log.i("KtvUiEvidence", "Saved rendered AVD screenshot: ${output.absolutePath}")
    }

    private fun exportScreenshotForReviewIfRequested(context: android.content.Context, bitmap: Bitmap, fileName: String) {
        if (InstrumentationRegistry.getArguments().getString("evidenceExport") != "true") return

        val relativeDirectory = "Pictures/HomeKtvUiEvidence/$runId"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativeDirectory)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Unable to create AVD screenshot evidence item for $fileName"
        }
        context.contentResolver.openOutputStream(uri, "w").use { stream ->
            checkNotNull(stream) { "Unable to open AVD screenshot evidence item for $fileName" }
                .let { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        Log.i("KtvUiEvidenceExport", "Saved screenshot to $relativeDirectory/$fileName")
    }

    private fun holdForEvidenceExport() {
        val milliseconds = InstrumentationRegistry.getArguments()
            .getString("evidenceHoldMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 15_000L)
            ?: 0L
        if (milliseconds > 0L) android.os.SystemClock.sleep(milliseconds)
    }

}
