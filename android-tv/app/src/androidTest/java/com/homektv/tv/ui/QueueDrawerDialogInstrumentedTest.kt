package com.homektv.tv.ui

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueDrawerDialogInstrumentedTest {
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
                ).apply {
                    onDismissDrawer = { dismissed = true }
                    show()
                    submitQueue("当前暂无歌曲播放", emptyList())
                }

                val drawerContent = requireNotNull(dialog?.window?.decorView)
                assertEquals(View.VISIBLE, drawerContent.findViewById<View>(R.id.txtDrawerEmpty).visibility)
                assertEquals(View.GONE, drawerContent.findViewById<View>(R.id.drawerRecyclerView).visibility)
            }

            instrumentation.waitForIdleSync()
            scenario.onActivity { dialog?.dismiss() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("drawer dismissal callback should run", dismissed)
                val initiatingButton = activity.window.decorView.findViewWithTag<Button>("queue-entry")
                // The activity may recreate its content during the test; resolve the current tagged entry.
                val source = initiatingButton ?: findQueueEntry(activity.window.decorView)
                assertTrue("focus should return to the control that opened the drawer", source?.hasFocus() == true)
            }
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
