package com.homektv.tv.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrawerActionPolicyTest {

    @Test
    fun pendingQueueAction_isDisabledUntilControllerFinishes() {
        assertFalse(
            QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = setOf(ActionKey("control:shuffle")),
            ),
        )
        assertTrue(
            QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = emptySet(),
            ),
        )
    }
}
