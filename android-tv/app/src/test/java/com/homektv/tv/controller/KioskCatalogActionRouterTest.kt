package com.homektv.tv.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskCatalogActionRouterTest {

    @Test
    fun routesKioskCatalogRequestsToControllerCatalogActions() {
        val fake = RecordingCatalogActions()
        val router = KioskCatalogActionRouter(fake)

        router.search("zjl")
        router.defaultSongs()
        router.artists()
        router.rankings()
        router.newSongs()
        router.artistSongs("artist-key")
        router.languages()
        router.languageSongs("粤语")
        router.tags()
        router.tagSongs("经典")

        assertEquals(
            listOf(
                "query:zjl",
                "query:",
                "artists",
                "ranking",
                "new-songs",
                "artist-songs:artist-key",
                "languages",
                "language-songs:粤语",
                "tags",
                "tag-songs:经典",
            ),
            fake.calls,
        )
    }

    private class RecordingCatalogActions : ControllerCatalogActions {
        val calls = mutableListOf<String>()

        override fun setQuery(value: String) {
            calls += "query:$value"
        }

        override fun loadMoreSearch() = Unit
        override fun loadRanking() {
            calls += "ranking"
        }

        override fun loadNewSongs() {
            calls += "new-songs"
        }

        override fun loadArtists(gender: String, initial: String, restorePage: Int) {
            calls += "artists"
        }

        override fun loadArtistSongs(artistKey: String, restorePage: Int) {
            calls += "artist-songs:$artistKey"
        }

        override fun loadLanguageSongs(language: String, restorePage: Int) {
            calls += "language-songs:$language"
        }
        override fun loadTagSongs(tag: String, restorePage: Int) {
            calls += "tag-songs:$tag"
        }
        override fun loadLanguages() {
            calls += "languages"
        }
        override fun loadTags() {
            calls += "tags"
        }
        override fun loadMoreCatalog() = Unit
    }
}
