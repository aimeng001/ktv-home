package com.homektv.tv.ui

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvQueueRaceConditionTest {

    private fun dummySnapshot(entries: List<QueueEntry>): QueueSnapshot =
        QueueSnapshot(
            playing = null,
            list = entries,
            state = "idle",
            volume = 60,
            muted = false,
            vocalMode = "accompaniment",
            tvOnline = true,
            connectedPhones = 1L,
        )

    @Test
    fun orderTop_whenBothSucceed_returnsTopSnapshotAndSuccessMessage() = kotlinx.coroutines.runBlocking {
        val song = SongDto(id = 88L, title = "晴天", artist = "周杰伦")
        val orderSnapshot = dummySnapshot(
            listOf(
                QueueEntry(queueId = 1L, song = SongDto(id = 1L, title = "海阔天空"), status = "waiting"),
                QueueEntry(queueId = 2L, song = song, status = "waiting"),
            ),
        )
        val topSnapshot = dummySnapshot(
            listOf(
                QueueEntry(queueId = 2L, song = song, status = "waiting"),
                QueueEntry(queueId = 1L, song = SongDto(id = 1L, title = "海阔天空"), status = "waiting"),
            ),
        )

        var topCalledWith: Long? = null
        val outcome = QueueOrderTopPolicy.resolve(
            orderResult = KtvApiResult.Success(orderSnapshot),
            songId = 88L,
            topInvoker = { qId: Long ->
                topCalledWith = qId
                KtvApiResult.Success(topSnapshot)
            },
        )

        assertEquals(2L, topCalledWith)
        assertTrue(outcome is OrderTopOutcome.SuccessTop)
        val success = outcome as OrderTopOutcome.SuccessTop
        assertEquals(topSnapshot, success.snapshot)
        assertEquals(2L, success.snapshot.list.first().queueId)
    }

    @Test
    fun orderTop_whenTopFails_fallsBackToOrderSnapshotWithWarning() = kotlinx.coroutines.runBlocking {
        val song = SongDto(id = 88L, title = "晴天", artist = "周杰伦")
        val orderSnapshot = dummySnapshot(
            listOf(QueueEntry(queueId = 5L, song = song, status = "waiting")),
        )

        val outcome = QueueOrderTopPolicy.resolve(
            orderResult = KtvApiResult.Success(orderSnapshot),
            songId = 88L,
            topInvoker = { _: Long ->
                KtvApiResult.Failure(KtvApiError(KtvApiErrorKind.BUSINESS, "NO_PERM", "仅房主可置顶"))
            },
        )

        assertTrue(outcome is OrderTopOutcome.FallbackOrderOnly)
        val fallback = outcome as OrderTopOutcome.FallbackOrderOnly
        assertEquals(orderSnapshot, fallback.snapshot)
        assertTrue(fallback.warning.contains("置顶失败"))
    }

    @Test
    fun orderTop_whenOrderFails_returnsOrderFailed() = kotlinx.coroutines.runBlocking {
        val outcome = QueueOrderTopPolicy.resolve(
            orderResult = KtvApiResult.Failure(KtvApiError(KtvApiErrorKind.BUSINESS, "SONG_NOT_READY", "曲目准备中")),
            songId = 88L,
            topInvoker = { _: Long -> error("Should not be called") },
        )

        assertTrue(outcome is OrderTopOutcome.OrderFailed)
        assertEquals("曲目准备中", (outcome as OrderTopOutcome.OrderFailed).error)
    }

    @Test
    fun orderTop_whenPlayingDirectly_returnsSuccessTopWithoutWarning() = kotlinx.coroutines.runBlocking {
        val song = SongDto(id = 88L, title = "晴天", artist = "周杰伦")
        val orderSnapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 10L, song = song),
            list = emptyList(),
            state = "playing",
            volume = 60,
            muted = false,
            vocalMode = "accompaniment",
            tvOnline = true,
            connectedPhones = 1L,
        )

        var topInvoked = false
        val outcome = QueueOrderTopPolicy.resolve(
            orderResult = KtvApiResult.Success(orderSnapshot),
            songId = 88L,
            topInvoker = { _: Long ->
                topInvoked = true
                KtvApiResult.Success(orderSnapshot)
            },
        )

        org.junit.Assert.assertFalse(topInvoked)
        assertTrue(outcome is OrderTopOutcome.SuccessTop)
        val success = outcome as OrderTopOutcome.SuccessTop
        assertEquals("已优先播放", success.message)
        assertEquals(orderSnapshot, success.snapshot)
    }

    @Test
    fun orderTop_whenSameSongAlreadyPlaying_promotesNewQueueEntryToTop() = kotlinx.coroutines.runBlocking {
        val song = SongDto(id = 88L, title = "晴天", artist = "周杰伦")
        val newWaitingEntry = QueueEntry(queueId = 99L, song = song, status = "waiting")
        val orderSnapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 10L, song = song),
            list = listOf(newWaitingEntry),
            state = "playing",
            volume = 60,
            muted = false,
            vocalMode = "accompaniment",
            tvOnline = true,
            connectedPhones = 1L,
        )
        val toppedSnapshot = orderSnapshot.copy(
            list = listOf(newWaitingEntry),
        )

        var topInvokedQueueId: Long? = null
        val outcome = QueueOrderTopPolicy.resolve(
            orderResult = KtvApiResult.Success(orderSnapshot),
            songId = 88L,
            topInvoker = { qId: Long ->
                topInvokedQueueId = qId
                KtvApiResult.Success(toppedSnapshot)
            },
        )

        // Must call top on the newly added waiting entry (99L), NOT treat as already playing
        assertEquals(99L, topInvokedQueueId)
        assertTrue(outcome is OrderTopOutcome.SuccessTop)
        assertEquals("已优先插播", (outcome as OrderTopOutcome.SuccessTop).message)
    }
}
