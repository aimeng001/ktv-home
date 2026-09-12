package com.homektv.tv.controller

import com.homektv.tv.net.QueueEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePermissionPolicyTest {
    @Test
    fun onlyStableOwnerIdOrRoomHostCanSeeQueueMutations() {
        val owned = QueueEntry(queueId = 1L, orderedBy = 7L)
        val other = QueueEntry(queueId = 2L, orderedBy = 8L)
        val legacy = QueueEntry(queueId = 3L, orderedBy = null)

        assertTrue(QueuePermissionPolicy.canManage(owned, 7L, false))
        assertFalse(QueuePermissionPolicy.canManage(other, 7L, false))
        assertFalse(QueuePermissionPolicy.canManage(legacy, 7L, false))
        assertTrue(QueuePermissionPolicy.canManage(other, null, true))
    }
}
