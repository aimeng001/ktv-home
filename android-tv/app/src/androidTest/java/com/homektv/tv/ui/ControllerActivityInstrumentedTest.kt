package com.homektv.tv.ui

import android.content.Intent
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import com.homektv.tv.ui.kiosk.KtvDashboardTile
import com.homektv.tv.ui.controller.ControllerListAdapter
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
                "seven-tile dashboard" to R.layout.view_ktv_commercial_dashboard,
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
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val width = activity.resources.displayMetrics.widthPixels.coerceAtLeast(320)
                val height = activity.resources.displayMetrics.heightPixels.coerceAtLeast(480)
                val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                val inflater = LayoutInflater.from(activity)

                layoutCases.forEach { (label, layoutId) ->
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
                assertEquals("all primary UI surfaces are listed", 16, layoutCases.size)
            }
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
                    onTileClick = dispatched::add
                }
                activity.setContentView(dashboard)
            }
            instrumentation.waitForIdleSync()

            val tileRoots = scenario.let {
                mutableListOf<View>().also { roots ->
                    it.onActivity {
                        roots += dashboard.binding.tilePinyin.tileRoot
                        roots += dashboard.binding.tileSinger.tileRoot
                        roots += dashboard.binding.tileCategory.tileRoot
                        roots += dashboard.binding.tileLanguage.tileRoot
                        roots += dashboard.binding.tileFavorites.tileRoot
                        roots += dashboard.binding.tileHistory.tileRoot
                        roots += dashboard.binding.tileQueue.tileRoot
                    }
                }
            }
            scenario.onActivity { activity ->
                isTelevision = activity.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_LEANBACK,
                ) || (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                val entries = KtvDashboardTile.dashboardEntries
                assertEquals(7, entries.size)
                tileRoots.forEachIndexed { index, tileRoot ->
                    assertTrue("visible dashboard tiles must be focusable", tileRoot.isFocusable)
                    assertEquals(
                        entries[index].title,
                        tileRoot.findViewById<TextView>(R.id.txtTileTitle).text.toString(),
                    )
                    assertTrue("dashboard tile click must be delivered", tileRoot.performClick())
                }
                assertEquals(entries, dispatched)

                dashboard.updateQueueCount(3)
                assertEquals(
                    "当前排队 3 首歌曲",
                    dashboard.binding.tileQueue.txtTileSubtitle.text.toString(),
                )
                dashboard.updateQueueCount(0)
                assertEquals(
                    "暂无排队歌曲",
                    dashboard.binding.tileQueue.txtTileSubtitle.text.toString(),
                )

                dashboard.lastFocusedTile = KtvDashboardTile.RANKING
                val focusRequested = dashboard.requestDashboardFocus()
                assertEquals(KtvDashboardTile.PINYIN, dashboard.lastFocusedTile)
                if (isTelevision) {
                    assertTrue("TV dashboard should accept initial remote focus", focusRequested)
                    assertSame(tileRoots[0], dashboard.findFocus())
                    val focusCases = listOf(
                        Triple(0, View.FOCUS_RIGHT, 1),
                        Triple(0, View.FOCUS_DOWN, 4),
                        Triple(1, View.FOCUS_LEFT, 0),
                        Triple(1, View.FOCUS_RIGHT, 2),
                        Triple(1, View.FOCUS_DOWN, 4),
                        Triple(2, View.FOCUS_LEFT, 1),
                        Triple(2, View.FOCUS_RIGHT, 3),
                        Triple(2, View.FOCUS_DOWN, 5),
                        Triple(3, View.FOCUS_LEFT, 2),
                        Triple(3, View.FOCUS_DOWN, 6),
                        Triple(4, View.FOCUS_UP, 0),
                        Triple(4, View.FOCUS_RIGHT, 5),
                        Triple(5, View.FOCUS_UP, 2),
                        Triple(5, View.FOCUS_LEFT, 4),
                        Triple(5, View.FOCUS_RIGHT, 6),
                        Triple(6, View.FOCUS_UP, 3),
                        Triple(6, View.FOCUS_LEFT, 5),
                    )
                    focusCases.forEach { (from, direction, expected) ->
                        assertSame(
                            "TV focus graph should reach tile $expected from $from in direction $direction",
                            tileRoots[expected],
                            FocusFinder.getInstance().findNextFocus(dashboard, tileRoots[from], direction),
                        )
                    }
                    listOf(4, 5, 6).forEach { index ->
                        assertEquals(R.id.btnPlayPause, tileRoots[index].nextFocusDownId)
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
