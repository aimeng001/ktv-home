package com.homektv.tv.ui

import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
                    onTileClick = {}
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
        if (!isTelevision) return

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
                assertTrue(overlayBinding.kioskDashboardView.binding.tilePinyin.tileRoot.hasFocus())
                assertEquals("分类", overlayBinding.tabCategories.text.toString())
                assertEquals("语种", overlayBinding.tabLanguages.text.toString())
                val density = activity.resources.displayMetrics.density
                val minimumTileGapPx = (10 * density).toInt()
                val tileGapPx = overlayBinding.kioskDashboardView.binding.tileSinger.tileRoot.left -
                    overlayBinding.kioskDashboardView.binding.tilePinyin.tileRoot.right
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

            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("DPAD_RIGHT should move focus from pinyin to singer", overlayBinding.kioskDashboardView.binding.tileSinger.tileRoot.hasFocus())
            }
            capture("07_dashboard_dpad_right_layout_fixture.png")

            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("DPAD_DOWN should follow the 4+3 focus graph", overlayBinding.kioskDashboardView.binding.tileFavorites.tileRoot.hasFocus())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("focus must continue from the home grid into the footer", overlayBinding.kioskBottomBar.binding.btnPlayPause.hasFocus())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { activity ->
                val overlayBinding = MainActivity::class.java
                    .getDeclaredField("kioskOverlayBinding")
                    .apply { isAccessible = true }
                    .get(activity) as com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
                assertTrue("footer focus must reach the queue drawer action", overlayBinding.kioskBottomBar.binding.btnQueueBadge.hasFocus())
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

    private fun capture(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(200L)
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
        bitmap.recycle()
        val metrics = context.resources.displayMetrics
        File(directory, "manifest.tsv").appendText(
            "$fileName\t${metrics.widthPixels}x${metrics.heightPixels}\tdensity=${metrics.density}\n",
        )
        Log.i("KtvUiEvidence", "Saved rendered AVD screenshot: ${output.absolutePath}")
    }
}
