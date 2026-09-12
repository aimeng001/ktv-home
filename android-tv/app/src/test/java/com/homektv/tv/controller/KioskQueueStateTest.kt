package com.homektv.tv.controller

import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.RoomHostStatus
import com.homektv.tv.net.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskQueueStateTest {

    @Test
    fun guestCannotManageAnotherUsersEntry() {
        val state = ControllerUiState(
            currentUser = UserProfile(id = 100L, nickname = "客厅"),
            roomHost = RoomHostStatus(isHost = false),
        )
        val entry = QueueEntry(queueId = 7L, orderedBy = 200L)

        assertFalse(QueuePermissionPolicy.canManage(entry, state))
    }

    @Test
    fun ownerCanManageOwnEntry() {
        val state = ControllerUiState(
            currentUser = UserProfile(id = 100L, nickname = "客厅"),
            roomHost = RoomHostStatus(isHost = false),
        )
        val entry = QueueEntry(queueId = 7L, orderedBy = 100L)

        assertTrue(QueuePermissionPolicy.canManage(entry, state))
    }

    @Test
    fun roomHostCanManageAnyEntry() {
        val state = ControllerUiState(
            currentUser = UserProfile(id = 100L, nickname = "客厅"),
            roomHost = RoomHostStatus(isHost = true),
        )
        val entry = QueueEntry(queueId = 7L, orderedBy = 200L)

        assertTrue(QueuePermissionPolicy.canManage(entry, state))
    }
}
