package com.homektv.tv.ui

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.net.RecentHistoryItem
import com.homektv.tv.net.SongDto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueDrawerDialogInstrumentedTest {
    @Test
    fun threeTabsLoadRealHistoryAndKeepUnavailableDownloadActionsDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        var dialog: KtvQueueDrawerDialog? = null
        var historyReadCount = 0
        val historyRowsReady = CountDownLatch(1)
        val repeatedHistoryIds = mutableListOf<Long>()

        try {
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
                    onHistoryTabSelected = { historyReadCount += 1 }
                    onRepeatHistory = { repeatedHistoryIds += it }
                    show()
                    selectInitialTab()
                }

                val root = requireNotNull(dialog?.window?.decorView)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.drawerQueuePage).visibility)
                root.findViewById<View>(R.id.drawerTabHistory).performClick()
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.drawerHistoryPage).visibility)
                val historyRecycler = requireNotNull(dialog?.window?.decorView)
                    .findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvHistoryDrawer)
                historyRecycler.adapter?.registerAdapterDataObserver(
                    object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            if (itemCount > 0) historyRowsReady.countDown()
                        }
                    },
                )
                dialog?.renderHistoryState(
                    history = listOf(
                        RecentHistoryItem(
                            historyId = 42L,
                            song = SongDto(id = 7L, title = "真实历史歌曲", artist = "测试歌手"),
                        ),
                    ),
                    loading = false,
                    errorMessage = null,
                    retryAvailable = false,
                )
            }

            instrumentation.waitForIdleSync()
            assertTrue("history response should reach the rendered list", historyRowsReady.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals("history tab entry should call the existing history action once", 1, historyReadCount)
                val root = requireNotNull(dialog?.window?.decorView)
                val historyRecycler = root
                    .findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvHistoryDrawer)
                assertEquals(View.VISIBLE, historyRecycler.visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.txtHistoryEmpty).visibility)
                val historyRow = requireNotNull(historyRecycler.findViewHolderForAdapterPosition(0)).itemView
                assertTrue("history rows should be D-pad focusable", historyRow.isFocusable)
                historyRow.performClick()
                assertEquals(listOf(42L), repeatedHistoryIds)

                root.findViewById<View>(R.id.drawerTabDownloads).performClick()
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.drawerDownloadsPage).visibility)
                listOf(
                    R.id.btnDownloadStartAll,
                    R.id.btnDownloadPauseAll,
                    R.id.btnDownloadDeleteAll,
                ).forEach { id ->
                    val button = root.findViewById<Button>(id)
                    assertEquals(View.VISIBLE, button.visibility)
                    assertTrue("download operation must remain disabled without a task source", !button.isEnabled)
                    button.performClick()
                }
                assertEquals("disabled download controls must not invoke a write action", listOf(42L), repeatedHistoryIds)
            }
        } finally {
            scenario.onActivity { dialog?.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun emptyQueueIsVisibleAndDismissingDrawerRestoresInitiatingFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        var dialog: KtvQueueDrawerDialog? = null
        var dismissed = false

        try {
            scenario.onActivity { activity ->
                val initiatingButton = Button(activity).apply {
                    tag = String(charArrayOf('q', 'u', 'e', 'u', 'e', '-', 'e', 'n', 't', 'r', 'y'))
                    text = "已点歌曲"
                    isFocusableInTouchMode = true
                }
                val root = FrameLayout(activity).apply {
                    isFocusableInTouchMode = true
                    addView(
                        initiatingButton,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                activity.setContentView(root)
                assertTrue("the queue entry must own focus before opening the drawer", initiatingButton.requestFocus())

                dialog = KtvQueueDrawerDialog(
                    context = activity,
                    currentUserId = null,
                    isHost = false,
                    coordinator = null,
                    onTop = {},
                    onCancel = {},
                    onShuffle = {},
                    initiatingFocusView = initiatingButton,
                ).apply {
                    onDismissDrawer = { dismissed = true }
                    show()
                    assertFalse(
                        "opening the modal drawer must clear the visible background focus",
                        initiatingButton.hasFocus(),
                    )
                    submitQueue("当前暂无歌曲播放", emptyList())
                }

                val drawerContent = requireNotNull(dialog?.window?.decorView)
                assertEquals(View.VISIBLE, drawerContent.findViewById<View>(R.id.txtDrawerEmpty).visibility)
                assertEquals(View.GONE, drawerContent.findViewById<View>(R.id.drawerRecyclerView).visibility)
            }

            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val backgroundEntry = findQueueEntry(activity.window.decorView)
                assertFalse(
                    "background controls must leave the D-pad focus path while a modal drawer is open",
                    backgroundEntry?.isFocusable == true,
                )
                assertNull(
                    "the activity window must not retain a focus target while the drawer owns focus",
                    activity.window.decorView.findFocus(),
                )
            }
            scenario.onActivity { dialog?.dismiss() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("drawer dismissal callback should run", dismissed)
                val initiatingButton = activity.window.decorView.findViewWithTag<Button>("queue-entry")
                // The activity may recreate its content during the test; resolve the current tagged entry.
                val source = initiatingButton ?: findQueueEntry(activity.window.decorView)
                assertTrue("background D-pad focusability must be restored when the drawer closes", source?.isFocusable == true)
                assertTrue("focus should return to the control that opened the drawer", source?.hasFocus() == true)
            }
        } finally {
            scenario.onActivity { dialog?.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun unresolvedQueueShowsUnavailableStateAndKeepsARealRetryAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<ControllerActivity>(
            Intent(instrumentation.targetContext, ControllerActivity::class.java)
                .putExtra(ControllerActivity.EXTRA_REALTIME, false),
        )
        var dialog: KtvQueueDrawerDialog? = null
        var retryCount = 0

        try {
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
                    onRetryQueue = { retryCount += 1 }
                    show()
                    showUnavailable("点歌服务离线，队列状态尚未同步。", retryAvailable = true)
                }

                val root = requireNotNull(dialog?.window?.decorView)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.txtDrawerQueueStatus).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.txtDrawerEmpty).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.drawerRecyclerView).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btnDrawerRetry).visibility)
                root.findViewById<Button>(R.id.btnDrawerRetry).performClick()
            }

            instrumentation.waitForIdleSync()
            assertEquals(1, retryCount)
        } finally {
            scenario.onActivity { dialog?.dismiss() }
            scenario.close()
        }
    }

    private fun findQueueEntry(root: View): Button? {
        if (root is Button && root.tag == "queue-entry") return root
        val group = root as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findQueueEntry(group.getChildAt(index))?.let { return it }
        }
        return null
    }
}
