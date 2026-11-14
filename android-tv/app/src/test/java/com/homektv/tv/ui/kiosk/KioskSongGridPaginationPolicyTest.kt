package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskSongGridPaginationPolicyTest {

    @Test
    fun `slicePage never returns more than 15 songs per page`() {
        val songs = (1..50).toList()

        val page0 = KioskSongGridPaginationPolicy.slicePage(
            items = songs,
            requestedPageIndex = 0,
            reportedTotal = 120L,
            hasMore = true,
        )
        assertEquals(15, page0.items.size)
        assertEquals((1..15).toList(), page0.items)
        assertEquals(0, page0.pageIndex)
        assertEquals(8, page0.totalPages)
        assertEquals("1 / 8", page0.pageLabel)

        val page1 = KioskSongGridPaginationPolicy.slicePage(
            items = songs,
            requestedPageIndex = 1,
            reportedTotal = 120L,
            hasMore = true,
        )
        assertEquals(15, page1.items.size)
        assertEquals((16..30).toList(), page1.items)
        assertEquals(1, page1.pageIndex)
        assertEquals("2 / 8", page1.pageLabel)

        val page3 = KioskSongGridPaginationPolicy.slicePage(
            items = songs,
            requestedPageIndex = 3,
            reportedTotal = 50L,
            hasMore = false,
        )
        assertEquals(5, page3.items.size)
        assertEquals((46..50).toList(), page3.items)
        assertEquals(3, page3.pageIndex)
        assertEquals("4 / 4", page3.pageLabel)
    }

    @Test
    fun `slicePage clamps out of range index cleanly including empty list`() {
        val emptySlice = KioskSongGridPaginationPolicy.slicePage(
            items = emptyList<Int>(),
            requestedPageIndex = 4,
            reportedTotal = 0L,
            hasMore = false,
        )
        assertTrue(emptySlice.items.isEmpty())
        assertEquals(0, emptySlice.pageIndex)
        assertEquals(1, emptySlice.totalPages)
        assertEquals("1 / 1", emptySlice.pageLabel)

        val overSlice = KioskSongGridPaginationPolicy.slicePage(
            items = (1..20).toList(),
            requestedPageIndex = 9,
            reportedTotal = 20L,
            hasMore = false,
        )
        assertEquals(1, overSlice.pageIndex)
        assertEquals((16..20).toList(), overSlice.items)
        assertEquals("2 / 2", overSlice.pageLabel)
    }

    @Test
    fun `resolveTurnPage advances within loaded songs or requests backend when crossing boundary`() {
        assertEquals(
            KioskSongGridPaginationPolicy.TurnPageAction.ShowPage(1),
            KioskSongGridPaginationPolicy.resolveTurnPage(
                currentPageIndex = 0,
                delta = 1,
                loadedCount = 30,
                hasMore = true,
            ),
        )

        assertEquals(
            KioskSongGridPaginationPolicy.TurnPageAction.LoadMoreForPage(2),
            KioskSongGridPaginationPolicy.resolveTurnPage(
                currentPageIndex = 1,
                delta = 1,
                loadedCount = 30,
                hasMore = true,
            ),
        )

        assertEquals(
            KioskSongGridPaginationPolicy.TurnPageAction.Stay,
            KioskSongGridPaginationPolicy.resolveTurnPage(
                currentPageIndex = 1,
                delta = 1,
                loadedCount = 30,
                hasMore = false,
            ),
        )

        assertEquals(
            KioskSongGridPaginationPolicy.TurnPageAction.ShowPage(0),
            KioskSongGridPaginationPolicy.resolveTurnPage(
                currentPageIndex = 1,
                delta = -1,
                loadedCount = 30,
                hasMore = true,
            ),
        )

        assertEquals(
            KioskSongGridPaginationPolicy.TurnPageAction.Stay,
            KioskSongGridPaginationPolicy.resolveTurnPage(
                currentPageIndex = 0,
                delta = -1,
                loadedCount = 30,
                hasMore = true,
            ),
        )
    }

    @Test
    fun `isBottomRowIndex identifies row 5 on full page and last occupied row on partial page`() {
        assertFalse(KioskSongGridPaginationPolicy.isBottomRowIndex(0, itemCountOnPage = 15))
        assertFalse(KioskSongGridPaginationPolicy.isBottomRowIndex(11, itemCountOnPage = 15))
        assertTrue(KioskSongGridPaginationPolicy.isBottomRowIndex(12, itemCountOnPage = 15))
        assertTrue(KioskSongGridPaginationPolicy.isBottomRowIndex(14, itemCountOnPage = 15))

        // Partial page with 7 items (rows 0, 1, 2): row 2 starts at index 6
        assertFalse(KioskSongGridPaginationPolicy.isBottomRowIndex(5, itemCountOnPage = 7))
        assertTrue(KioskSongGridPaginationPolicy.isBottomRowIndex(6, itemCountOnPage = 7))
    }
}
