package com.homektv.tv.controller

import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResultLruCacheTest {
    @Test
    fun cachesSongsArtistsAndArtistSongsAndInvalidatesOnRevisionChange() {
        var nowMs = 1_000L
        val cache = SearchResultLruCache(maxEntries = 32, ttlMs = 30_000L, clockMs = { nowMs })
        val song = SongDto(id = 1L, title = "知我", artist = "国风堂")
        val artist = ArtistItem(artistKey = "gft", name = "国风堂")

        cache.putSongs("ZW", catalogRevision = 5L, songs = listOf(song), lastPage = 0, hasMore = false)
        cache.putArtists("女", "G", "", "GFT", catalogRevision = 5L, artists = listOf(artist), lastPage = 0, total = 1L, hasMore = false)
        cache.putArtistSongs("gft", "ZW", catalogRevision = 5L, songs = listOf(song), lastPage = 0, total = 1L, hasMore = false)

        assertNotNull(cache.getSongs("ZW", catalogRevision = 5L))
        assertNull(cache.getSongs("ZW", catalogRevision = 6L))

        val cachedArtists = cache.getArtists("女", "G", "", "GFT", catalogRevision = 5L)
        assertEquals(listOf(artist), cachedArtists?.artists)
        assertNull(cache.getArtists("女", "G", "", "GFT", catalogRevision = 6L))

        val cachedArtistSongs = cache.getArtistSongs("gft", "ZW", catalogRevision = 5L)
        assertEquals(listOf(song), cachedArtistSongs?.songs)

        nowMs += 30_001L
        assertNull(cache.getSongs("ZW", catalogRevision = 5L))
        assertNull(cache.getArtistSongs("gft", "ZW", catalogRevision = 5L))
    }

    @Test
    fun evictsLeastRecentlyUsedEntryBeyondMaxCapacity() {
        var nowMs = 1_000L
        val cache = SearchResultLruCache(maxEntries = 2, ttlMs = 30_000L, clockMs = { nowMs })
        cache.putSongs("A", 1L, listOf(SongDto(id = 1L, title = "A")), 0, false)
        cache.putSongs("B", 1L, listOf(SongDto(id = 2L, title = "B")), 0, false)
        // Touch A so B becomes the least-recently-used key.
        assertNotNull(cache.getSongs("A", 1L))
        cache.putSongs("C", 1L, listOf(SongDto(id = 3L, title = "C")), 0, false)

        assertEquals(2, cache.size())
        assertNotNull(cache.getSongs("A", 1L))
        assertNull(cache.getSongs("B", 1L))
        assertNotNull(cache.getSongs("C", 1L))
    }
}
