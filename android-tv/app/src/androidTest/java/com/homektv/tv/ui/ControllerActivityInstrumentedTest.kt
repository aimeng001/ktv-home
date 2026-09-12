package com.homektv.tv.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.ui.controller.ControllerListAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke coverage for the native controller shell. */
@RunWith(AndroidJUnit4::class)
class ControllerActivityInstrumentedTest {
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
                assertTrue(findText(root, "交换声道"))
                assertTrue(findContentDescription(root, "搜索歌曲"))

                val lists = findRecyclerViews(root)
                assertTrue("controller panels must use virtualized lists", lists.size >= 4)
                assertTrue("panel lists must participate in outer scrolling", lists.all { it.isNestedScrollingEnabled })
            }
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

    private fun findContentDescription(view: View, expected: String): Boolean {
        if (view.contentDescription?.toString() == expected) return true
        return (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).any { index ->
                findContentDescription(group.getChildAt(index), expected)
            }
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
