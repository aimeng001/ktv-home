package com.homektv.tv.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerViewModelDefaultSongsTest {

    @Test
    fun defaultSongsInvokesRankingToPopulateRecommendedSongs() {
        val fake = RecordingCatalogActions()
        val router = KioskCatalogActionRouter(fake)

        router.defaultSongs()

        // Must clear query and request ranking
        assertTrue("defaultSongs must clear query", fake.calls.contains("query:"))
        assertTrue("defaultSongs must request ranking", fake.calls.contains("ranking"))
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

        override fun loadKioskAllSongs() {
            calls += "all-songs"
        }

        override fun loadMoreKioskAllSongs() {
            calls += "more-all-songs"
        }

        override fun stopKioskAllSongs() {
            calls += "stop-all-songs"
        }

        override fun loadArtists(gender: String, initial: String, restorePage: Int, region: String) {
            calls += "artists"
        }

        override fun loadArtistSongs(artistKey: String, restorePage: Int) {
            calls += "artist-songs:$artistKey"
        }

        override fun loadLanguages() {
            calls += "languages"
        }

        override fun loadLanguageSongs(language: String, restorePage: Int) {
            calls += "language-songs:$language"
        }

        override fun loadTags() {
            calls += "tags"
        }

        override fun loadTagSongs(tag: String, restorePage: Int) {
            calls += "tag-songs:$tag"
        }

        override fun loadMoreCatalog() = Unit
    }
}
