package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.SongPage
import com.homektv.tv.net.SongDto

/** TV-only all-library browsing; it must not reuse the phone catalog selection state. */
data class KioskLibraryBrowseState(
    val songs: List<SongDto> = emptyList(),
    val page: Int = 0,
    val total: Long = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: KtvApiError? = null,
    internal val requestId: Long = 0,
)

internal object KioskLibraryBrowseReducer {
    fun started(requestId: Long): KioskLibraryBrowseState =
        KioskLibraryBrowseState(loading = true, requestId = requestId)

    fun stopped(state: KioskLibraryBrowseState, requestId: Long): KioskLibraryBrowseState = state.copy(
        loading = false,
        loadingMore = false,
        requestId = requestId,
    )

    fun pageStarted(state: KioskLibraryBrowseState): KioskLibraryBrowseState =
        state.copy(loadingMore = true, error = null)

    fun pageSucceeded(
        state: KioskLibraryBrowseState,
        requestId: Long,
        page: Int,
        result: SongPage,
        maxItems: Int,
    ): KioskLibraryBrowseState {
        if (state.requestId != requestId) return state
        val merged = (if (page == 0) result.items else state.songs + result.items)
            .distinctBy(SongDto::id)
            .take(maxItems)
        val hasMore = merged.size < maxItems && result.items.isNotEmpty() &&
            if (result.total > 0L) (page.toLong() + 1L) * result.size.coerceAtLeast(1) < result.total
            else result.items.size >= result.size.coerceAtLeast(1)
        return state.copy(
            songs = merged,
            page = page,
            total = result.total.coerceAtLeast(0L),
            hasMore = hasMore,
            loading = false,
            loadingMore = false,
            error = null,
        )
    }

    fun failed(
        state: KioskLibraryBrowseState,
        requestId: Long,
        error: KtvApiError,
    ): KioskLibraryBrowseState = if (state.requestId != requestId) state else state.copy(
        loading = false,
        loadingMore = false,
        error = error,
    )
}
