package com.homektv.tv.ui

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.ui.SetupActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a short real Android view hierarchy to prove keyboard actions stay reachable. */
@RunWith(AndroidJUnit4::class)
class KioskLandscapeContentVisibilityInstrumentedTest {
    @Test
    fun shortKeyboardViewportScrollsBottomActionsIntoViewAndBackToTop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<SetupActivity>(
            Intent(instrumentation.targetContext, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_FORCE_SETUP, true),
        )
        try {
            var keyboard: KtvKeyboardView? = null
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply {
                    isFocusableInTouchMode = true
                }
                keyboard = KtvKeyboardView(activity)
                root.addView(
                    keyboard,
                    FrameLayout.LayoutParams(dp(activity, 360), dp(activity, 190)),
                )
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            // The kiosk is operated by a remote. Leave the emulator's default touch mode
            // before asserting DPAD-focusable actions.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()

            var assertionFailure: Throwable? = null
            scenario.onActivity { activity ->
                try {
                    val activeKeyboard = requireNotNull(keyboard)
                    val scroll = activeKeyboard.binding.root as? ScrollView
                    assertNotNull("short keyboard view must expose its content through a ScrollView", scroll)
                    val scrollView = requireNotNull(scroll)
                    assertTrue("full QWERTY keyboard content must exceed the short viewport", scrollView.canScrollVertically(1))

                    val bottomAction = activeKeyboard.binding.btnSwitchIme
                    assertTrue("bottom action must accept focus", bottomAction.requestFocus())
                    assertTrue("focusing the bottom action must scroll it into view", scrollView.scrollY > 0)
                    assertFullyVisible(bottomAction, scrollView)

                    val topAction = activeKeyboard.binding.btnClear
                    assertTrue("top action must remain reachable", topAction.requestFocus())
                    assertFullyVisible(topAction, scrollView)

                    assertTrue("keyboard mode control must activate", activeKeyboard.binding.btnToggleT9.performClick())
                    assertTrue("T9 mode must render its three-column grid", activeKeyboard.binding.keyboardGrid.columnCount == 3)
                } catch (failure: Throwable) {
                    assertionFailure = failure
                }
            }
            instrumentation.waitForIdleSync()
            assertionFailure?.let { throw it }

            scenario.onActivity { activity ->
                val activeKeyboard = requireNotNull(keyboard)
                val scrollView = activeKeyboard.binding.root as ScrollView
                assertTrue("T9 keyboard must remain reachable in the short viewport", scrollView.canScrollVertically(1))
                assertTrue("T9 bottom action must accept remote focus", activeKeyboard.binding.btnSwitchIme.requestFocus())
                assertTrue("T9 bottom action must scroll into view", scrollView.scrollY > 0)
                assertFullyVisible(activeKeyboard.binding.btnSwitchIme, scrollView)
            }
        } finally {
            scenario.close()
        }
    }

    private fun assertFullyVisible(child: View, viewport: View) {
        val childRect = Rect()
        val viewportRect = Rect()
        assertTrue("focused keyboard action must be attached to the display", child.getGlobalVisibleRect(childRect))
        assertTrue("keyboard scroll viewport must be attached to the display", viewport.getGlobalVisibleRect(viewportRect))
        assertTrue("focused action must not be clipped above its viewport", childRect.top >= viewportRect.top)
        assertTrue("focused action must not be clipped below its viewport", childRect.bottom <= viewportRect.bottom)
    }

    private fun dp(activity: SetupActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
