package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.RoomHostStatus
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerStateReducerTest {
    @Test
    fun websocketSnapshotReplacesQueueAndMarksOnline() {
        val state = ControllerUiState(
            connection = ControllerConnection.CONNECTING,
            loading = true,
            writing = true,
        )
        val snapshot = QueueSnapshot(
            playing = NowPlaying(queueId = 7, song = SongDto(42, "晴天", "周杰伦"), orderedByNick = "小明"),
            state = "playing",
            tvOnline = true,
        )

        val updated = ControllerStateReducer.withSnapshot(state, snapshot)

        assertEquals(7L, updated.queue.playing?.queueId)
        assertEquals(ControllerConnection.ONLINE, updated.connection)
        assertTrue(updated.loading)
        assertTrue(updated.writing)
    }

    @Test
    fun olderRevisionedSnapshotCannotReplaceNewerQueueState() {
        val current = ControllerUiState(
            queue = QueueSnapshot(
                stateRevision = 12L,
                playing = NowPlaying(queueId = 12L),
            ),
        )
        val stale = QueueSnapshot(
            stateRevision = 11L,
            playing = NowPlaying(queueId = 11L),
        )

        val updated = ControllerStateReducer.withSnapshot(current, stale)

        assertEquals(12L, updated.queue.stateRevision)
        assertEquals(12L, updated.queue.playing?.queueId)
    }

    @Test
    fun legacySnapshotCannotReplaceRevisionedQueueState() {
        val current = ControllerUiState(
            queue = QueueSnapshot(
                stateRevision = 12L,
                playing = NowPlaying(queueId = 12L),
            ),
        )

        val updated = ControllerStateReducer.withSnapshot(current, QueueSnapshot(
            stateRevision = 0L,
            playing = NowPlaying(queueId = 1L),
        ))

        assertEquals(12L, updated.queue.stateRevision)
        assertEquals(12L, updated.queue.playing?.queueId)
    }

    @Test
    fun unrelatedFailureDoesNotCancelSearchOrWriteOperation() {
        val state = ControllerUiState(
            loading = true,
            searchLoadingMore = true,
            writing = true,
        )
        val updated = ControllerStateReducer.withFailure(
            state,
            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
        )

        assertTrue(updated.loading)
        assertTrue(updated.searchLoadingMore)
        assertTrue(updated.writing)
    }

    @Test
    fun searchFailureOnlyStopsSearchLoading() {
        val state = ControllerUiState(
            loading = true,
            searchLoadingMore = true,
            writing = true,
        )
        val updated = ControllerStateReducer.withSearchFailure(
            state,
            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
        )

        assertFalse(updated.loading)
        assertFalse(updated.searchLoadingMore)
        assertTrue(updated.writing)
    }

    @Test
    fun knownBusinessErrorsBecomeActionableChineseMessages() {
        assertEquals(
            "这首歌已在队列中",
            ControllerStateReducer.userMessage(
                KtvApiError(KtvApiErrorKind.BUSINESS, "SONG_IN_QUEUE", "ignored"),
            ),
        )
        assertEquals(
            "电视未连接",
            ControllerStateReducer.userMessage(
                KtvApiError(KtvApiErrorKind.BUSINESS, "TV_OFFLINE", "ignored"),
            ),
        )
        assertTrue(
            ControllerStateReducer.userMessage(
                KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "ignored"),
            ).isNotBlank(),
        )
        assertEquals(
            "请先配置点歌服务",
            ControllerStateReducer.userMessage(
                KtvApiError(KtvApiErrorKind.NETWORK, "SERVER_NOT_CONFIGURED", "ignored"),
            ),
        )
    }

    @Test
    fun searchPagesAppendWithoutDuplicateSongIdsAndExposeMoreState() {
        val firstPage = (1L..50L).map { id -> SongDto(id, "歌$id", "歌手") }
        val secondPage = listOf(SongDto(50, "重复", "歌手")) +
            (51L..60L).map { id -> SongDto(id, "歌$id", "歌手") }
        val first = ControllerStateReducer.withSearchResults(
            ControllerStateReducer.withSearchStarted(ControllerUiState(), "歌"),
            "歌",
            firstPage,
            page = 0,
        )
        val second = ControllerStateReducer.withSearchResults(first, "歌", secondPage, page = 1)

        assertEquals(50, first.results.size)
        assertTrue(first.searchHasMore)
        assertEquals(60, second.results.size)
        assertEquals(1, second.results.count { it.id == 50L })
        assertFalse(second.searchHasMore)
    }

    @Test
    fun searchStateStopsAppendingAfterTheMemoryBudget() {
        val page = (1L..50L).map { id -> SongDto(id, "歌$id", "歌手") }
        var state = ControllerStateReducer.withSearchStarted(ControllerUiState(), "歌")
        repeat(41) { index ->
            val offset = index * 50L
            state = ControllerStateReducer.withSearchResults(
                state,
                "歌",
                page.map { song -> song.copy(id = song.id + offset) },
                page = index,
            )
        }

        assertEquals(2000, state.results.size)
        assertFalse(state.searchHasMore)
    }

    @Test
    fun staleRoomHostEventsCannotReplaceNewerRevision() {
        val current = ControllerUiState(
            roomHost = RoomHostStatus(claimed = true, hostUserId = 9L, revision = 3L),
        )
        val updated = ControllerStateReducer.withRoomHost(
            current,
            RoomHostStatus(claimed = false, revision = 2L),
        )

        assertTrue(updated.roomHost.claimed)
        assertEquals(9L, updated.roomHost.hostUserId)
        assertEquals(3L, updated.roomHost.revision)
    }

    @Test
    fun successfulReadClearsStaleFailureFeedback() {
        val updated = ControllerStateReducer.withSuccessfulRead(
            ControllerUiState(
                error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
                message = "无法连接点歌服务",
            ),
        )

        assertNull(updated.error)
        assertNull(updated.message)
    }

    @Test
    fun legacyRoomHostStatusCannotReplaceRevisionedState() {
        val current = ControllerUiState(
            roomHost = RoomHostStatus(claimed = true, hostUserId = 9L, revision = 3L),
        )

        val updated = ControllerStateReducer.withRoomHost(
            current,
            RoomHostStatus(claimed = false, revision = 0L),
        )

        assertTrue(updated.roomHost.claimed)
        assertEquals(9L, updated.roomHost.hostUserId)
        assertEquals(3L, updated.roomHost.revision)
    }

    @Test
    fun hostStateIsDerivedFromCurrentUserId() {
        val nonHostState = ControllerUiState(
            currentUser = com.homektv.tv.net.UserProfile(8L, "手机B"),
            roomHost = RoomHostStatus(claimed = false, revision = 1L),
        )
        val incoming = RoomHostStatus(
            claimed = true,
            hostUserId = 7L,
            revision = 4L,
            isHost = true, // Even if server erroneously sent true
        )
        val updatedNonHost = ControllerStateReducer.withRoomHost(nonHostState, incoming)
        assertFalse(updatedNonHost.roomHost.isHost)

        val hostState = nonHostState.copy(currentUser = com.homektv.tv.net.UserProfile(7L, "手机A"))
        val updatedHost = ControllerStateReducer.withRoomHost(hostState, incoming)
        assertTrue(updatedHost.roomHost.isHost)
    }

    @Test
    fun registrationSuccessRecomputesRoomHostState() {
        val state = ControllerUiState(
            roomHost = RoomHostStatus(claimed = true, hostUserId = 7L, revision = 4L, isHost = false),
        )
        val registered = RegistrationReducer.succeeded(
            state,
            com.homektv.tv.net.UserProfile(7L, "手机A"),
        )
        assertTrue(registered.roomHost.isHost)
    }
}
