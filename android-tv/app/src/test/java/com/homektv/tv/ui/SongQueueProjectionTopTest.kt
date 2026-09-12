package com.homektv.tv.ui

import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongQueueProjectionTopTest {

    @Test
    fun projectionRetainsQueueIdAndCalculatesCanManage() {
        val song1 = SongDto(id = 101L, title = "晴天")
        val song2 = SongDto(id = 102L, title = "海阔天空")
        val snapshot = QueueSnapshot(
            list = listOf(
                QueueEntry(queueId = 1L, status = "waiting", song = song1, orderedBy = 55L),
                QueueEntry(queueId = 2L, status = "waiting", song = song2, orderedBy = 99L),
            )
        )

        // User 55L: can manage own song, cannot manage other song
        val map = SongQueueProjection.from(snapshot, currentUserId = 55L, isRoomHost = false)
        val waiting1 = map[101L] as SongQueueState.Waiting
        val waiting2 = map[102L] as SongQueueState.Waiting

        assertEquals(1L, waiting1.queueId)
        assertTrue(waiting1.canManage)

        assertEquals(2L, waiting2.queueId)
        assertFalse(waiting2.canManage)
    }

    @Test
    fun roomHostHasCanManageOnAllWaitingSongs() {
        val song = SongDto(id = 102L, title = "海阔天空")
        val snapshot = QueueSnapshot(
            list = listOf(QueueEntry(queueId = 2L, status = "waiting", song = song, orderedBy = 99L))
        )

        val map = SongQueueProjection.from(snapshot, currentUserId = 55L, isRoomHost = true)
        val waiting = map[102L] as SongQueueState.Waiting
        assertTrue(waiting.canManage)
    }

    @Test
    fun defaultParametersMaintainBackwardsCompatibility() {
        val song = SongDto(id = 101L, title = "晴天")
        val snapshot = QueueSnapshot(
            list = listOf(QueueEntry(queueId = 1L, status = "waiting", song = song, orderedBy = 55L))
        )
        val map = SongQueueProjection.from(snapshot)
        val waiting = map[101L] as SongQueueState.Waiting
        assertEquals(1, waiting.position)
        assertEquals(1L, waiting.queueId)
        assertEquals(55L, waiting.orderedBy)
    }
}
