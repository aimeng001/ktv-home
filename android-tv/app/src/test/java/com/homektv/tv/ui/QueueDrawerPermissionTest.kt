package com.homektv.tv.ui

import com.homektv.tv.controller.ActionCoordinator
import com.homektv.tv.controller.ActionKey
import com.homektv.tv.controller.QueuePermissionPolicy
import com.homektv.tv.net.QueueEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrawerPermissionTest {

    @Test
    fun nonHostCannotManageOtherUserSong() {
        val entry = QueueEntry(queueId = 1L, song = null, orderedBy = 999L, orderedByNick = "OtherUser")
        assertFalse(QueuePermissionPolicy.canManage(entry, currentUserId = 100L, isRoomHost = false))
    }

    @Test
    fun ownerCanManageOwnSong() {
        val entry = QueueEntry(queueId = 2L, song = null, orderedBy = 100L, orderedByNick = "Me")
        assertTrue(QueuePermissionPolicy.canManage(entry, currentUserId = 100L, isRoomHost = false))
    }

    @Test
    fun hostCanManageAnySong() {
        val entry = QueueEntry(queueId = 3L, song = null, orderedBy = 999L, orderedByNick = "OtherUser")
        assertTrue(QueuePermissionPolicy.canManage(entry, currentUserId = 100L, isRoomHost = true))
    }

    @Test
    fun nonHostCannotManageUnownedSong() {
        val entry = QueueEntry(queueId = 4L, song = null, orderedBy = null, orderedByNick = "")
        assertFalse(QueuePermissionPolicy.canManage(entry, currentUserId = 100L, isRoomHost = false))
    }

    @Test
    fun hostCanManageUnownedSong() {
        val entry = QueueEntry(queueId = 5L, song = null, orderedBy = null, orderedByNick = "")
        assertTrue(QueuePermissionPolicy.canManage(entry, currentUserId = 100L, isRoomHost = true))
    }

    @Test
    fun actionCoordinator_preventsConcurrentDuplicateActions() {
        val coordinator = ActionCoordinator()
        val key = ActionKey("top", 501L)

        assertTrue(coordinator.tryStart(key))
        assertFalse(coordinator.tryStart(key)) // duplicate in-flight rejected

        coordinator.finish(key)
        assertTrue(coordinator.tryStart(key)) // allowed after finish
    }
}
