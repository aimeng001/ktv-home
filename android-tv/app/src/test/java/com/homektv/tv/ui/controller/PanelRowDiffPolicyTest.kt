package com.homektv.tv.ui.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRowDiffPolicyTest {
    @Test
    fun actionPanelRowsWithDifferentActionKeyAreNotSameContent() {
        val row1 = ActionPanelRow(
            id = -3L,
            text = "重试",
            enabled = true,
            contentDescription = "重试搜索",
            actionKey = "retry:abc",
            action = {},
        )
        val row2 = ActionPanelRow(
            id = -3L,
            text = "重试",
            enabled = true,
            contentDescription = "重试搜索",
            actionKey = "retry:def",
            action = {},
        )

        assertFalse(
            "Different actionKey must trigger rebind",
            PanelRowDiffPolicy.areContentsTheSame(row1, row2),
        )
    }

    @Test
    fun actionPanelRowsWithSameActionKeyAreSameContent() {
        val row1 = ActionPanelRow(
            id = -3L,
            text = "重试",
            enabled = true,
            contentDescription = "重试搜索",
            actionKey = "retry:abc",
            action = {},
        )
        val row2 = ActionPanelRow(
            id = -3L,
            text = "重试",
            enabled = true,
            contentDescription = "重试搜索",
            actionKey = "retry:abc",
            action = {},
        )

        assertTrue(
            "Matching actionKey with same properties are content identical",
            PanelRowDiffPolicy.areContentsTheSame(row1, row2),
        )
    }
}
