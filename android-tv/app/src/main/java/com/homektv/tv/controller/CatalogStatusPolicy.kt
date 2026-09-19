package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.LibraryStatus

enum class CatalogLoadState {
    LOADING,
    OFFLINE,
    HTTP_ERROR,
    ROOT_UNAVAILABLE,
    SCANNING,
    EMPTY,
    FILTER_EMPTY,
    READY,
    PARTIAL_FAILURE,
}

data class CatalogUiStatus(
    val state: CatalogLoadState = CatalogLoadState.LOADING,
    val revision: Long = 0,
    val code: String? = null,
    val message: String? = null,
)

/** Pure mapping from the public status contract to user-visible catalog states. */
object CatalogStatusPolicy {
    fun from(status: LibraryStatus?, hasItems: Boolean, hasFilter: Boolean): CatalogUiStatus {
        if (status == null) return CatalogUiStatus(CatalogLoadState.OFFLINE)
        val revision = status.catalogRevision.takeIf { it > 0 } ?: status.statusRevision
        // Older servers only returned totalSongs; do not mislabel that valid
        // response as a scan that never finishes.
        val legacy = status.libraryMode == "UNKNOWN" &&
            status.rootState == "UNKNOWN" && status.catalogRevision == 0L && status.statusRevision == 0L
        if (legacy) {
            return if (hasItems) CatalogUiStatus(CatalogLoadState.READY)
            else if (hasFilter) CatalogUiStatus(CatalogLoadState.FILTER_EMPTY)
            else CatalogUiStatus(CatalogLoadState.EMPTY)
        }
        if (status.rootState == "NOT_FOUND" || status.rootState == "NOT_READABLE") {
            return CatalogUiStatus(CatalogLoadState.ROOT_UNAVAILABLE, revision)
        }
        if (status.scanState == "RUNNING" || status.scanState == "IDLE" && status.phase != "COMPLETED") {
            return CatalogUiStatus(CatalogLoadState.SCANNING, revision)
        }
        if (status.scanState == "FAILED" || status.scanState == "PARTIAL" || !status.errorCode.isNullOrBlank()) {
            return CatalogUiStatus(CatalogLoadState.PARTIAL_FAILURE, revision, status.errorCode)
        }
        return when {
            hasItems -> CatalogUiStatus(CatalogLoadState.READY, revision)
            hasFilter -> CatalogUiStatus(CatalogLoadState.FILTER_EMPTY, revision)
            else -> CatalogUiStatus(CatalogLoadState.EMPTY, revision)
        }
    }

    fun fromError(error: KtvApiError, revision: Long = 0): CatalogUiStatus {
        val state = when (error.kind) {
            KtvApiErrorKind.NETWORK -> CatalogLoadState.OFFLINE
            else -> CatalogLoadState.HTTP_ERROR
        }
        return CatalogUiStatus(state, revision, error.code, error.message)
    }

    fun acceptsRevision(current: Long, incoming: Long): Boolean =
        incoming <= 0L || current <= 0L || incoming >= current
}
