package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.PlaylistSummary
import com.homektv.tv.net.PlaylistDetail
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.RecentHistoryItem
import com.homektv.tv.net.RoomHostStatus
import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.NamedCount
import com.homektv.tv.net.SongDto
import com.homektv.tv.net.SnapshotRevisionPolicy
import com.homektv.tv.net.UserProfile

enum class UiDomain {
    SEARCH,
    QUEUE,
    CATALOG,
    FAVORITES,
    PLAYLISTS,
    HISTORY,
    ROOM_HOST,
    REGISTRATION,
}

enum class ControllerConnection {
    CONNECTING,
    ONLINE,
    OFFLINE,
}

data class ControllerUiState(
    val connection: ControllerConnection = ControllerConnection.CONNECTING,
    /** Server-issued identity returned by POST /api/user; required for writes. */
    val currentUser: UserProfile? = null,
    val registration: RegistrationStatus = RegistrationStatus.IDLE,
    val query: String = "",
    val results: List<SongDto> = emptyList(),
    val searchPage: Int = 0,
    val searchHasMore: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val ranking: List<SongDto> = emptyList(),
    val newSongs: List<SongDto> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val artistInitials: List<String> = emptyList(),
    val artistGender: String = "",
    val artistInitial: String = "",
    val languages: List<NamedCount> = emptyList(),
    val tags: List<NamedCount> = emptyList(),
    val catalogSongs: List<SongDto> = emptyList(),
    val catalogPage: Int = 0,
    val catalogHasMore: Boolean = false,
    val catalogLoadingMore: Boolean = false,
    /** True while the first page of a catalog root is loading. */
    val catalogLoading: Boolean = false,
    val catalogDetail: Boolean = false,
    val catalogStatus: CatalogUiStatus = CatalogUiStatus(),
    val catalogStatusRevision: Long = 0,
    /** Stable artist key/language/tag used to restore a detail screen. */
    val catalogValue: String = "",
    val queue: QueueSnapshot = QueueSnapshot(),
    /** True until the first queue snapshot resolves and while an explicit refresh is in flight. */
    val queueLoading: Boolean = true,
    val queueProjection: Map<Long, com.homektv.tv.ui.SongQueueState> = emptyMap(),
    /** Only in-flight writes live here; queuedSongIds is derived from queue. */
    val pendingActions: Set<ActionKey> = emptySet(),
    val favoriteIds: Set<Long> = emptySet(),
    val favorites: List<SongDto> = emptyList(),
    val favoritesLoading: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
    val playlistDetail: PlaylistDetail? = null,
    val playlistDetailLoading: Boolean = false,
    val playlistCoverBytes: ByteArray? = null,
    val history: List<RecentHistoryItem> = emptyList(),
    val historyLoading: Boolean = false,
    val roomHost: RoomHostStatus = RoomHostStatus(),
    val loading: Boolean = false,
    val writing: Boolean = false,
    val message: String? = null,
    val error: KtvApiError? = null,
    val domainErrors: Map<UiDomain, KtvApiError> = emptyMap(),
) {
    fun errorFor(domain: UiDomain): KtvApiError? =
        domainErrors[domain] ?: if (domain == UiDomain.QUEUE && error != null && domainErrors.isEmpty()) error else null

    /**
     * Returns the user-facing message for one domain only. Callers rendering a
     * panel must use this instead of the legacy global message: a failed catalog
     * request must never make a successful history/favorites panel look failed.
     */
    fun messageFor(domain: UiDomain): String? =
        errorFor(domain)?.let(ControllerStateReducer::userMessage)
}

object ControllerStateReducer {
    fun withSnapshot(state: ControllerUiState, snapshot: QueueSnapshot): ControllerUiState {
        val currentRevision = state.queue.stateRevision
        val incomingRevision = snapshot.stateRevision
        // Once a revisioned snapshot has been accepted, a legacy or older REST/WS
        // response must not roll the controller back to an older queue state.
        if (!SnapshotRevisionPolicy.accepts(currentRevision, incomingRevision)) {
            return state
        }
        val nextDomainErrors = state.domainErrors - UiDomain.QUEUE
        val nextError = if (state.registration == RegistrationStatus.RETRY_REQUIRED) {
            state.error
        } else {
            nextDomainErrors.values.firstOrNull()
        }
        return state.copy(
            connection = ControllerConnection.ONLINE,
            queue = snapshot,
            queueLoading = false,
            queueProjection = com.homektv.tv.ui.SongQueueProjection.from(
                snapshot,
                state.currentUser?.id,
                state.roomHost.isHost,
            ),
            error = nextError,
            domainErrors = nextDomainErrors,
        )
    }

    fun withSearchStarted(state: ControllerUiState, query: String): ControllerUiState {
        val nextDomainErrors = state.domainErrors - UiDomain.SEARCH
        return state.copy(
            query = query,
            results = emptyList(),
            searchPage = 0,
            searchHasMore = query.isNotBlank(),
            searchLoadingMore = false,
            loading = query.isNotBlank(),
            error = nextDomainErrors.values.firstOrNull(),
            domainErrors = nextDomainErrors,
            message = null,
        )
    }

    fun withSearchResults(
        state: ControllerUiState,
        query: String,
        results: List<SongDto>,
        page: Int = 0,
        pageSize: Int = DEFAULT_SEARCH_PAGE_SIZE,
    ): ControllerUiState {
        val merged = (if (page <= 0) results else (state.results + results))
            .distinctBy { it.id }
            .take(MAX_SEARCH_RESULTS)
        val nextDomainErrors = state.domainErrors - UiDomain.SEARCH
        return state.copy(
            query = query,
            results = merged,
            searchPage = page.coerceAtLeast(0),
            searchHasMore = results.size >= pageSize && merged.size < MAX_SEARCH_RESULTS,
            searchLoadingMore = false,
            loading = false,
            error = nextDomainErrors.values.firstOrNull(),
            domainErrors = nextDomainErrors,
        )
    }

    fun withSearchLoadStarted(state: ControllerUiState): ControllerUiState {
        val nextDomainErrors = state.domainErrors - UiDomain.SEARCH
        return state.copy(
            searchLoadingMore = true,
            error = nextDomainErrors.values.firstOrNull(),
            domainErrors = nextDomainErrors,
            message = null,
        )
    }

    fun withConnection(state: ControllerUiState, connected: Boolean): ControllerUiState =
        state.copy(connection = if (connected) ControllerConnection.ONLINE else ControllerConnection.OFFLINE)

    /** A successful read clears stale domain feedback but preserves registration failure and other domains. */
    fun withSuccessfulRead(state: ControllerUiState, domain: UiDomain? = null): ControllerUiState {
        val nextDomainErrors = if (domain != null) state.domainErrors - domain else emptyMap()
        val nextError = if (state.registration == RegistrationStatus.RETRY_REQUIRED) {
            state.error
        } else {
            nextDomainErrors.values.firstOrNull()
        }
        return state.copy(
            error = nextError,
            domainErrors = nextDomainErrors,
            message = if (state.registration == RegistrationStatus.RETRY_REQUIRED) state.message else null,
            favoritesLoading = if (domain == UiDomain.FAVORITES) false else state.favoritesLoading,
            historyLoading = if (domain == UiDomain.HISTORY) false else state.historyLoading,
            queueLoading = if (domain == UiDomain.QUEUE) false else state.queueLoading,
        )
    }

    fun withQueueLoadStarted(state: ControllerUiState): ControllerUiState =
        withSuccessfulRead(state, UiDomain.QUEUE).copy(queueLoading = true)

    fun withPersonalLoadStarted(state: ControllerUiState, domain: UiDomain): ControllerUiState = when (domain) {
        UiDomain.FAVORITES -> withSuccessfulRead(state, domain).copy(
            favoritesLoading = true,
            historyLoading = false,
        )
        UiDomain.HISTORY -> withSuccessfulRead(state, domain).copy(
            favoritesLoading = false,
            historyLoading = true,
        )
        else -> state
    }

    fun withPersonalLoadFinished(state: ControllerUiState, domain: UiDomain): ControllerUiState = when (domain) {
        UiDomain.FAVORITES -> state.copy(favoritesLoading = false)
        UiDomain.HISTORY -> state.copy(historyLoading = false)
        else -> state
    }

    fun withPersonalLoadCancelled(state: ControllerUiState): ControllerUiState =
        state.copy(favoritesLoading = false, historyLoading = false)

    fun withCatalogRootSuccess(state: ControllerUiState): ControllerUiState =
        withSuccessfulRead(state, UiDomain.CATALOG).copy(
            catalogLoading = false,
            catalogLoadingMore = false,
        )

    fun withRoomHost(state: ControllerUiState, status: RoomHostStatus): ControllerUiState {
        if (status.revision < state.roomHost.revision) return state
        val isHost = status.claimed && state.currentUser?.id != null && status.hostUserId == state.currentUser.id
        val updatedRoomHost = status.copy(isHost = isHost)
        return state.copy(
            roomHost = updatedRoomHost,
            queueProjection = com.homektv.tv.ui.SongQueueProjection.from(
                state.queue,
                state.currentUser?.id,
                isHost,
            ),
        )
    }

    fun withDomainFailure(state: ControllerUiState, domain: UiDomain, error: KtvApiError): ControllerUiState =
        state.copy(
            catalogLoadingMore = if (domain == UiDomain.CATALOG) false else state.catalogLoadingMore,
            catalogLoading = if (domain == UiDomain.CATALOG) false else state.catalogLoading,
            favoritesLoading = if (domain == UiDomain.FAVORITES) false else state.favoritesLoading,
            historyLoading = if (domain == UiDomain.HISTORY) false else state.historyLoading,
            queueLoading = if (domain == UiDomain.QUEUE) false else state.queueLoading,
            error = error,
            domainErrors = state.domainErrors + (domain to error),
            message = userMessage(error),
        )

    fun withFailure(state: ControllerUiState, error: KtvApiError): ControllerUiState =
        withDomainFailure(state, UiDomain.QUEUE, error)

    fun withSearchFailure(state: ControllerUiState, error: KtvApiError): ControllerUiState =
        withDomainFailure(state, UiDomain.SEARCH, error).copy(
            loading = false,
            searchLoadingMore = false,
        )

    fun userMessage(error: KtvApiError): String = when (error.code) {
        "SONG_IN_QUEUE" -> "这首歌已在队列中"
        "SONG_NOT_READY" -> "歌曲尚未准备好，请稍后再试"
        "TV_OFFLINE" -> "电视未连接"
        "FORBIDDEN" -> "没有操作权限"
        "QUEUE_ITEM_NOT_FOUND" -> "队列项已不存在"
        "SERVER_NOT_CONFIGURED" -> "请先配置点歌服务"
        "NETWORK_ERROR" -> "无法连接点歌服务"
        "INVALID_RESPONSE", "INVALID_QUEUE" -> "服务端数据格式异常"
        "PAYLOAD_TOO_LARGE" -> "服务端响应过大"
        else -> when (error.kind) {
            KtvApiErrorKind.NETWORK -> "无法连接点歌服务"
            KtvApiErrorKind.HTTP -> "服务端暂时不可用"
            KtvApiErrorKind.DECODE -> "服务端数据格式异常"
            KtvApiErrorKind.PAYLOAD_TOO_LARGE -> "服务端响应过大"
            KtvApiErrorKind.BUSINESS -> error.message.ifBlank { "操作未完成" }
        }
    }

    private const val DEFAULT_SEARCH_PAGE_SIZE = 50
    private const val MAX_SEARCH_RESULTS = 2000
}
