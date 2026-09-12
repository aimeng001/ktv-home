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
import com.homektv.tv.net.UserProfile

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
    val catalogDetail: Boolean = false,
    /** Stable artist key/language/tag used to restore a detail screen. */
    val catalogValue: String = "",
    val queue: QueueSnapshot = QueueSnapshot(),
    val queueProjection: Map<Long, com.homektv.tv.ui.SongQueueState> = emptyMap(),
    /** Only in-flight writes live here; queuedSongIds is derived from queue. */
    val pendingActions: Set<ActionKey> = emptySet(),
    val favoriteIds: Set<Long> = emptySet(),
    val favorites: List<SongDto> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val playlistDetail: PlaylistDetail? = null,
    val playlistDetailLoading: Boolean = false,
    val playlistCoverBytes: ByteArray? = null,
    val history: List<RecentHistoryItem> = emptyList(),
    val roomHost: RoomHostStatus = RoomHostStatus(),
    val loading: Boolean = false,
    val writing: Boolean = false,
    val message: String? = null,
    val error: KtvApiError? = null,
)

object ControllerStateReducer {
    fun withSnapshot(state: ControllerUiState, snapshot: QueueSnapshot): ControllerUiState =
        state.copy(
            connection = ControllerConnection.ONLINE,
            queue = snapshot,
            queueProjection = com.homektv.tv.ui.SongQueueProjection.from(
                snapshot,
                state.currentUser?.id,
                state.roomHost.isHost,
            ),
            error = if (state.registration == RegistrationStatus.RETRY_REQUIRED) state.error else null,
        )

    fun withSearchStarted(state: ControllerUiState, query: String): ControllerUiState =
        state.copy(
            query = query,
            results = emptyList(),
            searchPage = 0,
            searchHasMore = query.isNotBlank(),
            searchLoadingMore = false,
            loading = query.isNotBlank(),
            error = null,
            message = null,
        )

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
        return state.copy(
            query = query,
            results = merged,
            searchPage = page.coerceAtLeast(0),
            searchHasMore = results.size >= pageSize && merged.size < MAX_SEARCH_RESULTS,
            searchLoadingMore = false,
            loading = false,
            error = null,
        )
    }

    fun withSearchLoadStarted(state: ControllerUiState): ControllerUiState =
        state.copy(searchLoadingMore = true, error = null, message = null)

    fun withConnection(state: ControllerUiState, connected: Boolean): ControllerUiState =
        state.copy(connection = if (connected) ControllerConnection.ONLINE else ControllerConnection.OFFLINE)

    /** A successful read clears stale page feedback but preserves registration failure. */
    fun withSuccessfulRead(state: ControllerUiState): ControllerUiState = state.copy(
        error = if (state.registration == RegistrationStatus.RETRY_REQUIRED) state.error else null,
        message = if (state.registration == RegistrationStatus.RETRY_REQUIRED) state.message else null,
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

    fun withFailure(state: ControllerUiState, error: KtvApiError): ControllerUiState =
        state.copy(
            catalogLoadingMore = false,
            error = error,
            message = userMessage(error),
        )

    fun withSearchFailure(state: ControllerUiState, error: KtvApiError): ControllerUiState =
        withFailure(state, error).copy(
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
