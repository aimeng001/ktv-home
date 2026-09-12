package com.homektv.tv.ui

import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongQueueProjectionTest {
    @Test
    fun emptySnapshotReturnsEmptyProjection() {
        val snapshot = QueueSnapshot()
        val projection = SongQueueProjection.from(snapshot)
        assertTrue(projection.isEmpty())
    }

    @Test
    fun playingSongIsProjectedAsPlaying() {
        val song = SongDto(id = 100L, title = "晴天", artist = "周杰伦")
        val snapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 1L, song = song, orderedByNick = "小明")
        )
        val projection = SongQueueProjection.from(snapshot)
        assertEquals(SongQueueState.Playing, projection[100L])
        assertNull(projection[200L])
    }

    @Test
    fun waitingSongsAreProjectedWith1BasedPositions() {
        val song1 = SongDto(id = 101L, title = "七里香", artist = "周杰伦")
        val song2 = SongDto(id = 102L, title = "青花瓷", artist = "周杰伦")
        val song3 = SongDto(id = 103L, title = "枫", artist = "周杰伦")
        val snapshot = QueueSnapshot(
            list = listOf(
                QueueEntry(queueId = 2L, song = song1, status = "waiting"),
                QueueEntry(queueId = 3L, song = song2, status = "finished"),
                QueueEntry(queueId = 4L, song = song3, status = "waiting"),
            )
        )
        val projection = SongQueueProjection.from(snapshot)
        val waiting1 = projection[101L] as SongQueueState.Waiting
        assertEquals(1, waiting1.position)
        assertEquals(2L, waiting1.queueId)
        assertNull(projection[102L]) // finished is not in waiting projection
        val waiting3 = projection[103L] as SongQueueState.Waiting
        assertEquals(2, waiting3.position)
        assertEquals(4L, waiting3.queueId)
    }

    @Test
    fun playingTakesPrecedenceOverDuplicateWaitingEntry() {
        val song = SongDto(id = 100L, title = "晴天", artist = "周杰伦")
        val snapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 1L, song = song),
            list = listOf(
                QueueEntry(queueId = 2L, song = song, status = "waiting")
            )
        )
        val projection = SongQueueProjection.from(snapshot)
        assertEquals(SongQueueState.Playing, projection[100L])
    }
}
