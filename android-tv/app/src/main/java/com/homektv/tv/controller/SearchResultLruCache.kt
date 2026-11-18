package com.homektv.tv.controller

import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.SongDto

internal sealed interface SearchCacheKey {
    val catalogRevision: Long

    data class Songs(
        val query: String,
        override val catalogRevision: Long,
    ) : SearchCacheKey

    data class Artists(
        val gender: String,
        val initial: String,
        val region: String,
        val keyword: String,
        override val catalogRevision: Long,
    ) : SearchCacheKey

    data class ArtistSongs(
        val artistKey: String,
        val keyword: String,
        override val catalogRevision: Long,
    ) : SearchCacheKey
}

internal data class CachedSongsSearch(
    val songs: List<SongDto>,
    val lastPage: Int,
    val hasMore: Boolean,
)

internal data class CachedArtistsSearch(
    val artists: List<ArtistItem>,
    val lastPage: Int,
    val total: Long,
    val hasMore: Boolean,
)

internal data class CachedArtistSongsSearch(
    val songs: List<SongDto>,
    val lastPage: Int,
    val total: Long,
    val hasMore: Boolean,
)

/**
 * Bounded in-memory LRU cache with TTL and catalog-revision keying so live
 * backspacing and history restoration resolve immediately without hitting the API.
 */
internal class SearchResultLruCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry<T>(
        val value: T,
        val cachedAtMs: Long,
    )

    private val entries = object : LinkedHashMap<SearchCacheKey, Entry<Any>>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchCacheKey, Entry<Any>>?): Boolean =
            size > maxEntries.coerceAtLeast(1)
    }

    @Synchronized
    fun getSongs(query: String, catalogRevision: Long): CachedSongsSearch? =
        getTyped(SearchCacheKey.Songs(query, catalogRevision))

    @Synchronized
    fun putSongs(
        query: String,
        catalogRevision: Long,
        songs: List<SongDto>,
        lastPage: Int,
        hasMore: Boolean,
    ) {
        put(
            SearchCacheKey.Songs(query, catalogRevision),
            CachedSongsSearch(songs.toList(), lastPage.coerceAtLeast(0), hasMore),
        )
    }

    @Synchronized
    fun getArtists(
        gender: String,
        initial: String,
        region: String,
        keyword: String,
        catalogRevision: Long,
    ): CachedArtistsSearch? =
        getTyped(SearchCacheKey.Artists(gender, initial, region, keyword, catalogRevision))

    @Synchronized
    fun putArtists(
        gender: String,
        initial: String,
        region: String,
        keyword: String,
        catalogRevision: Long,
        artists: List<ArtistItem>,
        lastPage: Int,
        total: Long,
        hasMore: Boolean,
    ) {
        put(
            SearchCacheKey.Artists(gender, initial, region, keyword, catalogRevision),
            CachedArtistsSearch(artists.toList(), lastPage.coerceAtLeast(0), total.coerceAtLeast(0L), hasMore),
        )
    }

    @Synchronized
    fun getArtistSongs(
        artistKey: String,
        keyword: String,
        catalogRevision: Long,
    ): CachedArtistSongsSearch? =
        getTyped(SearchCacheKey.ArtistSongs(artistKey, keyword, catalogRevision))

    @Synchronized
    fun putArtistSongs(
        artistKey: String,
        keyword: String,
        catalogRevision: Long,
        songs: List<SongDto>,
        lastPage: Int,
        total: Long,
        hasMore: Boolean,
    ) {
        put(
            SearchCacheKey.ArtistSongs(artistKey, keyword, catalogRevision),
            CachedArtistSongsSearch(songs.toList(), lastPage.coerceAtLeast(0), total.coerceAtLeast(0L), hasMore),
        )
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getTyped(key: SearchCacheKey): T? {
        val entry = entries[key] ?: return null
        val ageMs = clockMs() - entry.cachedAtMs
        if (ageMs < 0L || ageMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value as? T
    }

    private fun put(key: SearchCacheKey, value: Any) {
        entries[key] = Entry(value = value, cachedAtMs = clockMs())
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val DEFAULT_TTL_MS = 30_000L
    }
}
