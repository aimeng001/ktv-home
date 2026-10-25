package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.RoomHostStatus
import com.homektv.tv.net.SongDto
import com.homektv.tv.net.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerStateReducerTest {
    @Test
    fun queueLoadStateDistinguishesLoadingSuccessfulEmptyAndFailure() {
        val loading = ControllerUiState()
        assertTrue(loading.queueLoading)

        val emptySuccess = ControllerStateReducer.withSnapshot(loading, QueueSnapshot())
        assertFalse(emptySuccess.queueLoading)
        assertTrue(emptySuccess.queue.list.isEmpty())
        assertNull(emptySuccess.errorFor(UiDomain.QUEUE))

        val readSuccess = ControllerStateReducer.withSuccessfulRead(loading, UiDomain.QUEUE)
        assertFalse(readSuccess.queueLoading)

        val failed = ControllerStateReducer.withDomainFailure(
            loading,
            UiDomain.QUEUE,
            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
        )
        assertFalse(failed.queueLoading)
        assertTrue(failed.errorFor(UiDomain.QUEUE) != null)

        val retrying = ControllerStateReducer.withQueueLoadStarted(failed)
        assertTrue(retrying.queueLoading)
        assertNull(retrying.errorFor(UiDomain.QUEUE))
    }

    @Test
    fun staleQueueSnapshotDoesNotEndCurrentQueueLoad() {
        val current = ControllerUiState(
            queueLoading = true,
            queue = QueueSnapshot(stateRevision = 12L),
        )

        val updated = ControllerStateReducer.withSnapshot(
            current,
            QueueSnapshot(stateRevision = 11L),
        )

        assertTrue(updated.queueLoading)
        assertEquals(12L, updated.queue.stateRevision)
    }

    @Test
    fun equalRevisionSnapshotIsAcceptedAndUpdatesQueueProjectionForRegisteredUser() {
        val current = ControllerUiState(
            currentUser = UserProfile(7L, "房主"),
            queueLoading = true,
            queue = QueueSnapshot(stateRevision = 12L),
        )

        val updated = ControllerStateReducer.withSnapshot(
            current,
            QueueSnapshot(stateRevision = 12L),
        )

        assertEquals(12L, updated.queue.stateRevision)
        assertFalse(updated.queueLoading)
        assertEquals(ControllerConnection.ONLINE, updated.connection)
    }

    @Test
    fun personalReadLoadingTracksTheLatestFavoritesOrHistoryRequestAndClearsOnFailure() {
        val favorites = ControllerStateReducer.withPersonalLoadStarted(
            ControllerUiState(),
            UiDomain.FAVORITES,
        )
        assertTrue(favorites.favoritesLoading)
        assertFalse(favorites.historyLoading)

        val history = ControllerStateReducer.withPersonalLoadStarted(favorites, UiDomain.HISTORY)
        assertFalse(history.favoritesLoading)
        assertTrue(history.historyLoading)

        val failed = ControllerStateReducer.withPersonalLoadFinished(
            ControllerStateReducer.withDomainFailure(
                history,
                UiDomain.HISTORY,
                KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
            ),
            UiDomain.HISTORY,
        )
        assertFalse(failed.historyLoading)
        assertTrue(failed.errorFor(UiDomain.HISTORY) != null)
    }

    @Test
    fun successfulEmptyPersonalReadEndsLoadingWithoutInventingItems() {
        val loading = ControllerStateReducer.withPersonalLoadStarted(
            ControllerUiState(),
            UiDomain.FAVORITES,
        )
        val completed = ControllerStateReducer.withPersonalLoadFinished(
            ControllerStateReducer.withSuccessfulRead(loading, UiDomain.FAVORITES),
            UiDomain.FAVORITES,
        )

        assertFalse(completed.favoritesLoading)
        assertTrue(completed.favorites.isEmpty())
        assertNull(completed.errorFor(UiDomain.FAVORITES))
    }

    @Test
    fun successfulCatalogRootRequestEndsLoadingEvenWhenTheDirectoryIsEmpty() {
        val loading = ControllerUiState(
            catalogLoading = true,
            domainErrors = mapOf(
                UiDomain.CATALOG to KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "network"),
            ),
        )

        val loaded = ControllerStateReducer.withCatalogRootSuccess(loading)

        assertFalse(loaded.catalogLoading)
        assertNull(loaded.errorFor(UiDomain.CATALOG))
        assertTrue(loaded.languages.isEmpty())
        assertTrue(loaded.tags.isEmpty())
    }

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
    fun legacyQueueErrorFallbackAndDomainSpecificMessagesRemainIsolated() {
        val globalError = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline")
        val queueOnly = ControllerUiState(error = globalError)

        assertEquals(globalError, queueOnly.errorFor(UiDomain.QUEUE))
        assertNull(queueOnly.errorFor(UiDomain.CATALOG))
        assertEquals("无法连接点歌服务", queueOnly.messageFor(UiDomain.QUEUE))
        assertNull(queueOnly.messageFor(UiDomain.HISTORY))

        val otherDomainError = KtvApiError(KtvApiErrorKind.HTTP, "HTTP_ERROR", "catalog")
        val withOtherDomain = queueOnly.copy(domainErrors = mapOf(UiDomain.CATALOG to otherDomainError))
        assertNull(withOtherDomain.errorFor(UiDomain.QUEUE))
        assertEquals(otherDomainError, withOtherDomain.errorFor(UiDomain.CATALOG))
    }

    @Test
    fun eachDomainFailureStopsOnlyThatDomainsLoadIndicators() {
        UiDomain.entries.forEach { domain ->
            val before = ControllerUiState(
                catalogLoadingMore = true,
                catalogLoading = true,
                favoritesLoading = true,
                historyLoading = true,
                queueLoading = true,
            )

            val after = ControllerStateReducer.withDomainFailure(
                before,
                domain,
                KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", domain.name),
            )

            assertEquals(domain != UiDomain.CATALOG, after.catalogLoadingMore)
            assertEquals(domain != UiDomain.CATALOG, after.catalogLoading)
            assertEquals(domain != UiDomain.FAVORITES, after.favoritesLoading)
            assertEquals(domain != UiDomain.HISTORY, after.historyLoading)
            assertEquals(domain != UiDomain.QUEUE, after.queueLoading)
            assertEquals(domain, after.domainErrors.keys.single())
        }
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

    @Test
    fun blankSearchClearsResultsAndDoesNotStartLoading() {
        val current = ControllerUiState(
            query = "previous",
            results = listOf(SongDto(1L, "旧歌", "旧歌手")),
            searchPage = 3,
            searchHasMore = true,
            loading = true,
        )

        val updated = ControllerStateReducer.withSearchStarted(current, "  ")

        assertEquals("  ", updated.query)
        assertTrue(updated.results.isEmpty())
        assertEquals(0, updated.searchPage)
        assertFalse(updated.searchHasMore)
        assertFalse(updated.loading)
    }

    @Test
    fun connectionReducerMapsBothOnlineAndOfflineTransitions() {
        val online = ControllerStateReducer.withConnection(ControllerUiState(), true)
        val offline = ControllerStateReducer.withConnection(online, false)

        assertEquals(ControllerConnection.ONLINE, online.connection)
        assertEquals(ControllerConnection.OFFLINE, offline.connection)
    }

    @Test
    fun successfulReadsPreserveRegistrationRetryFeedbackAndCanClearAllDomainErrors() {
        val registrationError = KtvApiError(KtvApiErrorKind.NETWORK, "USER_REGISTRATION_FAILED", "retry")
        val state = ControllerUiState(
            registration = RegistrationStatus.RETRY_REQUIRED,
            error = registrationError,
            message = "请重试注册",
            domainErrors = mapOf(
                UiDomain.QUEUE to KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "queue"),
                UiDomain.CATALOG to KtvApiError(KtvApiErrorKind.HTTP, "HTTP_ERROR", "catalog"),
            ),
        )

        val updated = ControllerStateReducer.withSuccessfulRead(state)

        assertEquals(registrationError, updated.error)
        assertEquals("请重试注册", updated.message)
        assertTrue(updated.domainErrors.isEmpty())
    }

    @Test
    fun successfulQueueSnapshotPreservesRegistrationRetryError() {
        val registrationError = KtvApiError(KtvApiErrorKind.NETWORK, "USER_REGISTRATION_FAILED", "retry")
        val state = ControllerUiState(
            registration = RegistrationStatus.RETRY_REQUIRED,
            error = registrationError,
            domainErrors = mapOf(UiDomain.QUEUE to KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "queue")),
        )

        val updated = ControllerStateReducer.withSnapshot(state, QueueSnapshot(stateRevision = 1L))

        assertEquals(registrationError, updated.error)
        assertTrue(updated.domainErrors.isEmpty())
        assertFalse(updated.queueLoading)
    }

    @Test
    fun personalLoadingReducersLeaveUnrelatedDomainsUnchanged() {
        val state = ControllerUiState(catalogLoading = true, queueLoading = true)

        assertEquals(state, ControllerStateReducer.withPersonalLoadStarted(state, UiDomain.QUEUE))
        assertEquals(state, ControllerStateReducer.withPersonalLoadFinished(state, UiDomain.CATALOG))
    }

    @Test
    fun roomHostReducerAcceptsEqualRevisionButDoesNotTrustClaimOrHostFlagsWithoutIdentity() {
        val stateWithoutUser = ControllerUiState(
            roomHost = RoomHostStatus(claimed = true, hostUserId = 7L, revision = 3L, isHost = true),
        )
        val claimedWithoutUser = ControllerStateReducer.withRoomHost(
            stateWithoutUser,
            RoomHostStatus(claimed = true, hostUserId = 7L, revision = 3L, isHost = true),
        )
        assertTrue(claimedWithoutUser.roomHost.claimed)
        assertFalse(claimedWithoutUser.roomHost.isHost)
        assertEquals(3L, claimedWithoutUser.roomHost.revision)

        val stateWithUser = stateWithoutUser.copy(currentUser = UserProfile(7L, "房主"))
        val unclaimed = ControllerStateReducer.withRoomHost(
            stateWithUser,
            RoomHostStatus(claimed = false, hostUserId = 7L, revision = 4L, isHost = true),
        )
        assertFalse(unclaimed.roomHost.isHost)

        val claimedWithoutHostId = ControllerStateReducer.withRoomHost(
            stateWithUser,
            RoomHostStatus(claimed = true, hostUserId = null, revision = 5L, isHost = true),
        )
        assertFalse(claimedWithoutHostId.roomHost.isHost)
    }

    @Test
    fun mapsEveryKnownBusinessCodeAndEachFallbackErrorKind() {
        val knownMessages = mapOf(
            "SONG_IN_QUEUE" to "这首歌已在队列中",
            "SONG_NOT_READY" to "歌曲尚未准备好，请稍后再试",
            "TV_OFFLINE" to "电视未连接",
            "FORBIDDEN" to "没有操作权限",
            "QUEUE_ITEM_NOT_FOUND" to "队列项已不存在",
            "SERVER_NOT_CONFIGURED" to "请先配置点歌服务",
            "NETWORK_ERROR" to "无法连接点歌服务",
            "INVALID_RESPONSE" to "服务端数据格式异常",
            "INVALID_QUEUE" to "服务端数据格式异常",
            "PAYLOAD_TOO_LARGE" to "服务端响应过大",
        )
        knownMessages.forEach { (code, message) ->
            assertEquals(message, ControllerStateReducer.userMessage(
                KtvApiError(KtvApiErrorKind.BUSINESS, code, "ignored"),
            ))
        }

        assertEquals("无法连接点歌服务", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.NETWORK, "UNKNOWN", "ignored"),
        ))
        assertEquals("服务端暂时不可用", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.HTTP, "UNKNOWN", "ignored"),
        ))
        assertEquals("服务端数据格式异常", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.DECODE, "UNKNOWN", "ignored"),
        ))
        assertEquals("服务端响应过大", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.PAYLOAD_TOO_LARGE, "UNKNOWN", "ignored"),
        ))
        assertEquals("操作未完成", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.BUSINESS, "UNKNOWN", "  "),
        ))
        assertEquals("服务端说明", ControllerStateReducer.userMessage(
            KtvApiError(KtvApiErrorKind.BUSINESS, "UNKNOWN", "服务端说明"),
        ))
    }
}
