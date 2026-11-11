package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.SongDto
import com.homektv.tv.net.SongPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskLibraryBrowseStateTest {
    @Test
    fun browsePagesAreBoundedDeduplicatedAndRejectStaleRequests() {
        val initial = KioskLibraryBrowseReducer.started(requestId = 8L)
        assertTrue(initial.loading)

        val firstPage = KioskLibraryBrowseReducer.pageSucceeded(
            state = initial,
            requestId = 8L,
            page = 0,
            result = SongPage(items = listOf(song(1), song(2)), total = 3, page = 0, size = 2),
            maxItems = 10,
        )
        assertEquals(listOf(1L, 2L), firstPage.songs.map(SongDto::id))
        assertEquals(3L, firstPage.total)
        assertTrue(firstPage.hasMore)
        assertFalse(firstPage.loading)

        val loadingMore = KioskLibraryBrowseReducer.pageStarted(firstPage)
        val secondPage = KioskLibraryBrowseReducer.pageSucceeded(
            state = loadingMore,
            requestId = 8L,
            page = 1,
            result = SongPage(items = listOf(song(2), song(3)), total = 3, page = 1, size = 2),
            maxItems = 10,
        )
        assertEquals(listOf(1L, 2L, 3L), secondPage.songs.map(SongDto::id))
        assertFalse(secondPage.hasMore)
        assertFalse(secondPage.loadingMore)

        val stale = KioskLibraryBrowseReducer.pageSucceeded(
            state = KioskLibraryBrowseReducer.started(requestId = 9L),
            requestId = 8L,
            page = 0,
            result = SongPage(items = listOf(song(99)), total = 1, page = 0, size = 50),
            maxItems = 10,
        )
        assertTrue(stale.songs.isEmpty())
        assertEquals(9L, stale.requestId)
    }

    @Test
    fun failedAppendKeepsLoadedSongsAndRetryStateIsSeparateFromPhoneCatalog() {
        val initial = KioskLibraryBrowseReducer.started(requestId = 4L)
        val loaded = KioskLibraryBrowseReducer.pageSucceeded(
            state = initial,
            requestId = 4L,
            page = 0,
            result = SongPage(items = listOf(song(1)), total = 100, page = 0, size = 50),
            maxItems = 10,
        )
        val failed = KioskLibraryBrowseReducer.failed(
            KioskLibraryBrowseReducer.pageStarted(loaded),
            requestId = 4L,
            error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
        )

        assertEquals(listOf(1L), failed.songs.map(SongDto::id))
        assertFalse(failed.loadingMore)
        assertEquals("NETWORK_ERROR", failed.error?.code)
        assertNull(ControllerUiState().errorFor(UiDomain.CATALOG))
        assertTrue(ControllerUiState().catalogSongs.isEmpty())
    }

    @Test
    fun stoppedRequestRejectsLatePageResponseAndPreservesLoadedRows() {
        val loaded = KioskLibraryBrowseReducer.pageSucceeded(
            state = KioskLibraryBrowseReducer.started(requestId = 12L),
            requestId = 12L,
            page = 0,
            result = SongPage(items = listOf(song(1)), total = 100, page = 0, size = 50),
            maxItems = 100,
        )
        val inFlight = KioskLibraryBrowseReducer.pageStarted(loaded)
        val stopped = KioskLibraryBrowseReducer.stopped(inFlight, requestId = 13L)
        val lateResult = KioskLibraryBrowseReducer.pageSucceeded(
            state = stopped,
            requestId = 12L,
            page = 1,
            result = SongPage(items = listOf(song(2)), total = 100, page = 1, size = 50),
            maxItems = 100,
        )

        assertEquals(listOf(1L), stopped.songs.map(SongDto::id))
        assertFalse(stopped.loadingMore)
        assertEquals(stopped, lateResult)
    }

    private fun song(id: Long) = SongDto(id = id, title = "song-$id")
}
