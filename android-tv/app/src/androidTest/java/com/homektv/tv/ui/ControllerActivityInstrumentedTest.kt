package com.homektv.tv.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import com.homektv.tv.ui.kiosk.KtvDashboardTile
import com.homektv.tv.ui.controller.ControllerListAdapter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke coverage for the native controller shell. */
@RunWith(AndroidJUnit4::class)
class ControllerActivityInstrumentedTest {
    private val evidenceRunId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    @Test
    fun setupDiscoveryAndManualPagesSwitchWithoutClearingInputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<SetupActivity>(
            Intent(instrumentation.targetContext, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_FORCE_SETUP, true),
        )
        try {
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setupDiscoveryPage).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.setupManualPage).visibility)
                activity.findViewById<Button>(R.id.btnOpenManualSetup).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setupManualPage).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.setupDiscoveryPage).visibility)
                activity.findViewById<EditText>(R.id.inputHost).setText("192.0.2.1:8080")
                activity.findViewById<EditText>(R.id.inputCredential).setText("manual-flow-test")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val backButton = activity.findViewById<Button>(R.id.btnSetupBackToDiscovery)
                if (!activity.window.decorView.isInTouchMode) {
                    assertTrue("DPAD mode should focus the first control of the manual page", backButton.hasFocus())
                } else {
                    assertTrue("touch mode should leave the page control visible and enabled", backButton.isShown && backButton.isEnabled)
                }
                backButton.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setupDiscoveryPage).visibility)
                activity.findViewById<Button>(R.id.btnOpenManualSetup).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("192.0.2.1:8080", activity.findViewById<EditText>(R.id.inputHost).text.toString())
                assertEquals("manual-flow-test", activity.findViewById<EditText>(R.id.inputCredential).text.toString())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun adapterKeepsTheFiftyFirstItemReachableWithStableId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = ControllerListAdapter<Long>(
            itemId = { it },
            createView = { parent -> TextView(parent.context) },
            bindView = { view, item, _ -> (view as TextView).text = "歌曲$item" },
        )

        adapter.submitList((1L..100L).toList())

        assertEquals(100, adapter.itemCount)
        assertEquals(51L, adapter.getItemId(50))
    }

    @Test
    fun controllerExposesAccessibleRemoteAndSearchControls() {
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
                ControllerActivity::class.java,
            ).putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val phoneNav = activity.findViewById<LinearLayout>(R.id.phoneNav)
                if (phoneNav.visibility == View.VISIBLE) {
                    assertEquals(5, phoneNav.childCount)
                    (phoneNav.getChildAt(1) as Button).performClick()
                }
                val searchInput = activity.findViewById<EditText>(R.id.searchInput)
                assertTrue("search field must be visible and accessible", searchInput.isShown && searchInput.isImportantForAccessibility)
                assertEquals(activity.getString(R.string.controller_search_hint), searchInput.hint.toString())
                assertNull("a content description would mask the useful search hint", searchInput.contentDescription)
                if (phoneNav.visibility == View.VISIBLE) {
                    (phoneNav.getChildAt(3) as Button).performClick()
                }
                assertTrue(findText(root, "交换声道"))

                val lists = findRecyclerViews(root)
                assertTrue("controller panels must use virtualized lists", lists.size >= 4)
                assertTrue("panel lists must participate in outer scrolling", lists.all { it.isNestedScrollingEnabled })
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun phoneNavigationSwitchesOnlyTheSelectedProductArea() {
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                ControllerActivity::class.java,
            ).putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            scenario.onActivity { activity ->
                val navigation = activity.findViewById<LinearLayout>(R.id.phoneNav)
                if (navigation.visibility != View.VISIBLE) {
                    assertEquals("TV and tablet use the existing multi-panel layout", View.GONE, navigation.visibility)
                    return@onActivity
                }

                val expectedTabs = listOf("目录", "搜索", "队列", "遥控", "我的")
                val panelIds = listOf(
                    R.id.catalogTabsContainer,
                    R.id.searchInput,
                    R.id.queueTitle,
                    R.id.remoteTitle,
                    R.id.personalTabsContainer,
                )
                assertEquals(expectedTabs.size, navigation.childCount)
                val panels: List<View> = panelIds.map { viewId -> activity.findViewById<View>(viewId) }

                expectedTabs.forEachIndexed { index, title ->
                    val tab = navigation.getChildAt(index) as Button
                    assertEquals(title, tab.text.toString())
                    tab.performClick()
                    assertTrue("$title panel should be visible", panels[index].isShown)
                    when (index) {
                        0 -> assertTrue(activity.findViewById<View>(R.id.catalogPhoneEntries).isShown)
                        3 -> assertTrue(activity.findViewById<View>(R.id.remoteNowPlayingCard).isShown)
                        4 -> assertTrue(activity.findViewById<View>(R.id.myConnectionCard).isShown)
                    }
                    panels.forEachIndexed { panelIndex, panel ->
                        if (panelIndex != index) assertFalse("unselected panel must be hidden", panel.isShown)
                    }
                }
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun phoneControllerFollowsCurrentDisplayOnNarrowTouchscreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expectedOrientationName = InstrumentationRegistry.getArguments()
            .getString("expectedOrientation")
            ?.lowercase()
        assertTrue(
            "the matrix runner must explicitly supply expectedOrientation=portrait or landscape",
            expectedOrientationName == "portrait" || expectedOrientationName == "landscape",
        )
        val expectedOrientation = if (expectedOrientationName == "landscape") {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        } else {
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        val evidenceName = InstrumentationRegistry.getArguments()
            .getString("evidenceName")
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "phone_controller_current_display"
        assertTrue(
            "this orientation contract applies to a touchscreen phone AVD",
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN),
        )
        assertFalse(
            "a TV AVD must not be used for the phone orientation contract",
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
        )

        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(context, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(
                    "the test AVD must exercise the narrow-phone branch",
                    activity.resources.configuration.smallestScreenWidthDp < 600,
                )
                assertEquals(
                    "phone controller should leave orientation to the current device display",
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    activity.requestedOrientation,
                )
                assertEquals(
                    "controller should follow the requested matrix orientation",
                    expectedOrientation,
                    activity.resources.configuration.orientation,
                )
                val decorView = activity.window.decorView
                assertTrue("matrix display should be measured before screenshot", decorView.width > 0 && decorView.height > 0)
                val boundsOrientation = if (decorView.width > decorView.height) {
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                } else {
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                assertEquals("window bounds should match the requested matrix orientation", expectedOrientation, boundsOrientation)
                Log.i(
                    "KtvUiEvidence",
                    "matrix-orientation:$evidenceName config=${activity.resources.configuration.orientation} " +
                        "decor=${decorView.width}x${decorView.height}",
                )
                assertTrue("narrow phone layout should expose phone navigation", activity.findViewById<View>(R.id.phoneNav).isShown)
                val navigation = activity.findViewById<LinearLayout>(R.id.phoneNav)
                assertTrue("catalog tab should be clickable", (navigation.getChildAt(0) as Button).performClick())
                val singers = activity.findViewById<View>(R.id.btnPhoneCatalogArtists)
                assertTrue("singers catalog entry should be visible", singers.isShown)
                assertTrue("singers catalog entry should open", singers.performClick())
                assertEquals("歌手", activity.findViewById<TextView>(R.id.catalogTitle).text.toString())
            }
            instrumentation.waitForIdleSync()
            captureScreen("$evidenceName.png")
            scenario.onActivity { activity ->
                captureViewHierarchy(activity.window.decorView, "$evidenceName-view.xml")
            }
            val captureHoldMillis = InstrumentationRegistry.getArguments()
                .getString("captureHoldMillis")
                ?.toLongOrNull()
                ?.coerceIn(0L, 20_000L)
                ?: 0L
            Log.i("KtvUiEvidence", "capture-ready:$evidenceName holdMillis=$captureHoldMillis")
            if (captureHoldMillis > 0L) SystemClock.sleep(captureHoldMillis)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun phoneSetupFollowsCurrentDisplayOnNarrowTouchscreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expectedOrientationName = InstrumentationRegistry.getArguments()
            .getString("expectedOrientation")
            ?.lowercase()
        assertTrue(
            "the matrix runner must explicitly supply expectedOrientation=portrait or landscape",
            expectedOrientationName == "portrait" || expectedOrientationName == "landscape",
        )
        val expectedOrientation = if (expectedOrientationName == "landscape") {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        } else {
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        assertTrue(
            "this orientation contract applies to a touchscreen phone AVD",
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN),
        )
        assertFalse(
            "a TV AVD must not be used for the phone orientation contract",
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
        )

        val scenario = ActivityScenario.launch<SetupActivity>(
            Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_FORCE_SETUP, true),
        )
        try {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(
                    "the test AVD must exercise the narrow-phone branch",
                    activity.resources.configuration.smallestScreenWidthDp < 600,
                )
                assertEquals(
                    "phone setup should leave orientation to the current device display",
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    activity.requestedOrientation,
                )
                assertEquals(
                    "setup should follow the requested matrix orientation",
                    expectedOrientation,
                    activity.resources.configuration.orientation,
                )
                val decorView = activity.window.decorView
                assertTrue("setup window should be measured", decorView.width > 0 && decorView.height > 0)
                val boundsOrientation = if (decorView.width > decorView.height) {
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                } else {
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                assertEquals("setup window bounds should match the requested matrix orientation", expectedOrientation, boundsOrientation)
                assertTrue("setup discovery page should be visible", activity.findViewById<View>(R.id.setupDiscoveryPage).isShown)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun leavingPhoneSearchForQueueHidesImeAndRestoresContentViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            scenario.onActivity { activity ->
                val navigation = activity.findViewById<LinearLayout>(R.id.phoneNav)
                assertEquals("this behavior applies to the phone's five-area navigation", View.VISIBLE, navigation.visibility)
                assertEquals(5, navigation.childCount)
                (navigation.getChildAt(1) as Button).performClick()
                val searchInput = activity.findViewById<EditText>(R.id.searchInput)
                searchInput.requestFocus()
                searchInput.post {
                    val inputMethod = activity.getSystemService(InputMethodManager::class.java)
                    inputMethod.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            instrumentation.waitForIdleSync()
            val imeDeadline = SystemClock.elapsedRealtime() + 5_000L
            var imeVisible = false
            while (SystemClock.elapsedRealtime() < imeDeadline && !imeVisible) {
                scenario.onActivity { activity ->
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (!imeVisible) SystemClock.sleep(100L)
            }
            assertTrue("the search field should open the software keyboard", imeVisible)
            captureScreen("phone_search_keyboard_before_queue_actual.png")

            var contentHeightWithIme = 0
            scenario.onActivity { activity ->
                contentHeightWithIme = activity.findViewById<View>(R.id.contentScroll).height
                (activity.findViewById<LinearLayout>(R.id.phoneNav).getChildAt(2) as Button).performClick()
            }
            instrumentation.waitForIdleSync()

            var imeVisibleAfterNavigation = true
            var restoredContentHeight = 0
            val hideDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < hideDeadline && imeVisibleAfterNavigation) {
                scenario.onActivity { activity ->
                    imeVisibleAfterNavigation = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    restoredContentHeight = activity.findViewById<View>(R.id.contentScroll).height
                }
                if (imeVisibleAfterNavigation) SystemClock.sleep(100L)
            }
            assertFalse("leaving Search must dismiss the keyboard before Queue is shown", imeVisibleAfterNavigation)
            assertTrue("the queue panel must regain the area previously occupied by the keyboard", restoredContentHeight > contentHeightWithIme)
            scenario.onActivity { activity ->
                assertTrue("the queue panel should remain visible after the keyboard closes", activity.findViewById<View>(R.id.queueTitle).isShown)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500L)
            instrumentation.waitForIdleSync()
            captureScreen("phone_queue_keyboard_dismissed_actual.png")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun primaryUiLayoutsInflateAndMeasureOnTheEmulator() {
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                ControllerActivity::class.java,
            ).putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        try {
            val layoutCases = listOf(
                "main player/standby" to R.layout.activity_main,
                "setup/connection" to R.layout.activity_setup,
                "controller panels" to R.layout.fragment_controller,
                "KTV overlay and pip" to R.layout.view_ktv_kiosk_overlay,
                "playback bottom bar" to R.layout.view_ktv_bottom_bar,
                "song search keyboard" to R.layout.view_ktv_keyboard,
                "queue drawer" to R.layout.dialog_ktv_queue,
                "QR dialog" to R.layout.dialog_ktv_qr,
                "dashboard tile" to R.layout.item_kiosk_dashboard_tile,
                "named count row" to R.layout.item_kiosk_named_count,
                "song row" to R.layout.item_kiosk_song_row,
                "singer card" to R.layout.item_singer_card,
                "queue row" to R.layout.item_queue_drawer_row,
                "history server row" to R.layout.item_history_server,
                "LAN server row" to R.layout.item_lan_server,
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var host: FrameLayout
            scenario.onActivity { activity ->
                host = FrameLayout(activity)
                activity.setContentView(host)
            }
            instrumentation.waitForIdleSync()

            layoutCases.forEach { (label, layoutId) ->
                scenario.onActivity { activity ->
                    val width = activity.resources.displayMetrics.widthPixels.coerceAtLeast(320)
                    val height = activity.resources.displayMetrics.heightPixels.coerceAtLeast(480)
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    val inflater = LayoutInflater.from(activity)

                    host.removeAllViews()
                    try {
                        inflater.inflate(layoutId, host, true)
                        assertTrue("$label must add a root view", host.childCount > 0)
                        val surface = host.getChildAt(0)
                        surface.visibility = View.VISIBLE
                        host.measure(widthSpec, heightSpec)
                        host.layout(0, 0, width, height)
                        assertTrue("$label must have measurable width", surface.width > 0)
                        assertTrue("$label must have measurable height", surface.height > 0)
                    } catch (failure: Throwable) {
                        throw AssertionError("$label failed to inflate or measure", failure)
                    }
                }
                instrumentation.waitForIdleSync()
            }
            assertEquals("all primary UI surfaces are listed", 16, layoutCases.size)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun dashboardTilesDispatchAndTvFocusGraphStaysOnVisibleEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(
                instrumentation.targetContext,
                ControllerActivity::class.java,
            ).putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        lateinit var dashboard: KtvCommercialDashboardView
        val dispatched = mutableListOf<KtvDashboardTile>()
        var isTelevision = false
        try {
            scenario.onActivity { activity ->
                dashboard = KtvCommercialDashboardView(activity).apply {
                    onTileSelected = dispatched::add
                }
                activity.setContentView(dashboard)
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                isTelevision = activity.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_LEANBACK,
                ) || (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                val entries = KtvDashboardTile.dashboardEntries
                val tileViews = listOf(
                    Triple(KtvDashboardTile.HOT_RANKING, dashboard.binding.tileHotRanking, dashboard.binding.txtHotRankingTitle),
                    Triple(KtvDashboardTile.NEW_SONGS, dashboard.binding.tileNewSongs, dashboard.binding.txtNewSongsTitle),
                    Triple(KtvDashboardTile.SINGER, dashboard.binding.tileSinger, dashboard.binding.txtSingerTileTitle),
                    Triple(KtvDashboardTile.SONG_NAME, dashboard.binding.tileSongName, dashboard.binding.txtSongNameTitle),
                    Triple(KtvDashboardTile.LANGUAGE, dashboard.binding.tileLanguage, dashboard.binding.txtLanguageTitle),
                    Triple(KtvDashboardTile.THEME, dashboard.binding.tileTheme, dashboard.binding.txtThemeTitle),
                    Triple(KtvDashboardTile.OPERA, dashboard.binding.tileOpera, dashboard.binding.txtOperaTitle),
                    Triple(KtvDashboardTile.TIKTOK, dashboard.binding.tileTikTok, dashboard.binding.txtTikTokTitle),
                    Triple(KtvDashboardTile.FAVORITES, dashboard.binding.tileFavorites, dashboard.binding.txtFavoritesTitle),
                    Triple(KtvDashboardTile.LOCAL, dashboard.binding.tileLocal, dashboard.binding.txtLocalTitle),
                )
                assertEquals(10, entries.size)
                assertEquals(entries, tileViews.map { it.first })
                tileViews.forEach { (tile, tileRoot, title) ->
                    assertTrue("${tile.title} must be focusable", tileRoot.isFocusable)
                    assertEquals(tile.title, title.text.toString())
                    assertTrue("${tile.title} click must be delivered", tileRoot.performClick())
                }
                assertEquals(entries, dispatched)

                dashboard.lastFocusedTile = KtvDashboardTile.SONG_NAME
                val focusRequested = dashboard.requestDashboardFocus()
                assertEquals(KtvDashboardTile.SONG_NAME, dashboard.lastFocusedTile)
                if (isTelevision) {
                    assertTrue("TV dashboard should accept initial remote focus", focusRequested)
                    assertSame(dashboard.binding.tileSongName, dashboard.findFocus())
                    val focusCases = listOf(
                        Triple(dashboard.binding.tileHotRanking, View.FOCUS_DOWN, dashboard.binding.tileNewSongs),
                        Triple(dashboard.binding.tileHotRanking, View.FOCUS_RIGHT, dashboard.binding.tileSinger),
                        Triple(dashboard.binding.tileNewSongs, View.FOCUS_RIGHT, dashboard.binding.tileSongName),
                        Triple(dashboard.binding.tileSinger, View.FOCUS_RIGHT, dashboard.binding.tileTikTok),
                        Triple(dashboard.binding.tileTikTok, View.FOCUS_LEFT, dashboard.binding.tileLanguage),
                        Triple(dashboard.binding.tileLanguage, View.FOCUS_DOWN, dashboard.binding.tileTheme),
                        Triple(dashboard.binding.tileTheme, View.FOCUS_DOWN, dashboard.binding.tileOpera),
                        Triple(dashboard.binding.tileTikTok, View.FOCUS_DOWN, dashboard.binding.tileFavorites),
                        Triple(dashboard.binding.tileFavorites, View.FOCUS_RIGHT, dashboard.binding.tileLocal),
                    )
                    focusCases.forEach { (from, direction, expected) ->
                        assertSame(
                            "TV focus graph should reach tile $expected from $from in direction $direction",
                            expected,
                            FocusFinder.getInstance().findNextFocus(dashboard, from, direction),
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()

        } finally {
            scenario.close()
        }
    }

    private fun findText(view: View, expected: String): Boolean {
        if (view is TextView && view.text?.toString() == expected) return true
        return (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).any { index -> findText(group.getChildAt(index), expected) }
        } ?: false
    }

    private fun captureScreen(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "AVD screenshot capture failed for $fileName"
        }
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir("ui-evidence")), evidenceRunId)
            .apply { check(mkdirs() || isDirectory) }
        FileOutputStream(File(directory, fileName)).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        if (InstrumentationRegistry.getArguments().getString("evidenceExport") == "true") {
            val relativeDirectory = "Pictures/HomeKtvUiEvidence/$evidenceRunId"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeDirectory)
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
            ) { "Unable to create screenshot evidence item for $fileName" }
            context.contentResolver.openOutputStream(uri, "w").use { stream ->
                checkNotNull(stream) { "Unable to open screenshot evidence item for $fileName" }
                    .let { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            Log.i("KtvUiEvidenceExport", "Saved screenshot to $relativeDirectory/$fileName")
        }
        bitmap.recycle()
    }

    private fun captureViewHierarchy(root: View, fileName: String) {
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy>")

        fun append(view: View) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val resourceId = if (view.id == View.NO_ID) "" else {
                runCatching { view.resources.getResourceName(view.id) }.getOrDefault("")
            }
            val text = (view as? TextView)?.text?.toString().orEmpty()
            val description = view.contentDescription?.toString().orEmpty()
            xml.append("<node")
                .append(" class=\"").append(escapeXml(view.javaClass.name)).append('"')
                .append(" resource-id=\"").append(escapeXml(resourceId)).append('"')
                .append(" text=\"").append(escapeXml(text)).append('"')
                .append(" content-desc=\"").append(escapeXml(description)).append('"')
                .append(" bounds=\"[").append(location[0]).append(',').append(location[1]).append("][")
                .append(location[0] + view.width).append(',').append(location[1] + view.height).append("]\"")
                .append(" clickable=\"").append(view.isClickable).append('"')
                .append(" focusable=\"").append(view.isFocusable).append('"')
                .append(" selected=\"").append(view.isSelected).append('"')
                .append(" enabled=\"").append(view.isEnabled).append('"')
                .append(" visible=\"").append(view.isShown).append('"')
                .append('>')
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) append(view.getChildAt(index))
            }
            xml.append("</node>")
        }

        append(root)
        xml.append("</hierarchy>")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir("ui-evidence")), evidenceRunId)
            .apply { check(mkdirs() || isDirectory) }
        File(directory, fileName).writeText(xml.toString(), Charsets.UTF_8)
    }

    private fun escapeXml(value: String): String = value
        .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun findRecyclerViews(view: View): List<RecyclerView> {
        val found = mutableListOf<RecyclerView>()
        if (view is RecyclerView) found += view
        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                found += findRecyclerViews(group.getChildAt(index))
            }
        }
        return found
    }
}
