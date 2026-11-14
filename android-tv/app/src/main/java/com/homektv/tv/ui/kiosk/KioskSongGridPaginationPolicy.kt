package com.homektv.tv.ui.kiosk

/**
 * 电视端 5×3 歌曲网格硬分页与遥控器翻页策略。
 *
 * 严格保证点歌台每页最多展示 15 首（3 列 × 5 行），禁止 RecyclerView 自动滚动或一次提交整批列表。
 */
object KioskSongGridPaginationPolicy {
    const val COLUMNS = 3
    const val ROWS = 5
    const val PAGE_SIZE = COLUMNS * ROWS

    data class PageSlice<T>(
        val pageIndex: Int,
        val totalPages: Int,
        val items: List<T>,
        val pageLabel: String,
    )

    sealed interface TurnPageAction {
        data class ShowPage(val targetPageIndex: Int) : TurnPageAction
        data class LoadMoreForPage(val targetPageIndex: Int) : TurnPageAction
        data object Stay : TurnPageAction
    }

    fun <T> slicePage(
        items: List<T>,
        requestedPageIndex: Int,
        reportedTotal: Long = items.size.toLong(),
        hasMore: Boolean = false,
        pageSize: Int = PAGE_SIZE,
    ): PageSlice<T> {
        val safePageSize = pageSize.coerceAtLeast(1)
        val loadedCount = items.size
        val loadedTotal = loadedCount.toLong()
        val minimumTotalWithMore = if (hasMore) loadedTotal + safePageSize else loadedTotal
        val effectiveTotal = maxOf(loadedTotal, reportedTotal, minimumTotalWithMore)
        val totalPages = ((effectiveTotal + safePageSize - 1) / safePageSize).toInt().coerceAtLeast(1)
        val loadedPages = ((loadedCount + safePageSize - 1) / safePageSize).coerceAtLeast(1)
        val pageIndex = requestedPageIndex.coerceIn(0, loadedPages - 1)
        val fromIndex = (pageIndex * safePageSize).coerceAtMost(loadedCount)
        val toIndex = (fromIndex + safePageSize).coerceAtMost(loadedCount)
        val slice = if (fromIndex < toIndex) items.subList(fromIndex, toIndex) else emptyList()
        return PageSlice(
            pageIndex = pageIndex,
            totalPages = totalPages,
            items = slice,
            pageLabel = "${pageIndex + 1} / $totalPages",
        )
    }

    fun resolveTurnPage(
        currentPageIndex: Int,
        delta: Int,
        loadedCount: Int,
        hasMore: Boolean,
        pageSize: Int = PAGE_SIZE,
    ): TurnPageAction {
        if (delta == 0) return TurnPageAction.Stay
        val safePageSize = pageSize.coerceAtLeast(1)
        val safeCurrent = currentPageIndex.coerceAtLeast(0)
        val targetPage = (safeCurrent + delta).coerceAtLeast(0)
        if (delta < 0) {
            return if (targetPage != safeCurrent) TurnPageAction.ShowPage(targetPage) else TurnPageAction.Stay
        }
        val targetStart = targetPage * safePageSize
        return when {
            targetStart < loadedCount -> TurnPageAction.ShowPage(targetPage)
            hasMore -> TurnPageAction.LoadMoreForPage(targetPage)
            else -> TurnPageAction.Stay
        }
    }

    fun isBottomRowIndex(
        adapterPosition: Int,
        itemCountOnPage: Int,
        columns: Int = COLUMNS,
    ): Boolean {
        if (adapterPosition < 0 || itemCountOnPage <= 0 || columns <= 0) return false
        val lastRow = (itemCountOnPage - 1) / columns
        val currentRow = adapterPosition / columns
        return currentRow >= lastRow
    }
}
