package com.homektv.tv.controller

/** Keeps TV catalog navigation on the controller's bounded/paginated request path. */
class KioskCatalogActionRouter(
    private val actions: ControllerCatalogActions,
) {
    fun search(keyword: String) = actions.setQuery(keyword)

    fun defaultSongs() {
        actions.setQuery("")
        actions.loadRanking()
    }

    fun allSongs() = actions.loadKioskAllSongs()

    fun moreAllSongs() = actions.loadMoreKioskAllSongs()

    fun stopAllSongs() = actions.stopKioskAllSongs()

    fun artists() = actions.loadArtists(gender = "", initial = "", restorePage = 0)

    fun searchArtists(gender: String, initial: String, region: String = "", keyword: String) =
        actions.searchArtists(gender = gender, initial = initial, region = region, keyword = keyword)

    fun searchArtistSongs(artistKey: String, keyword: String) =
        actions.searchArtistSongs(artistKey = artistKey, keyword = keyword)

    fun rankings() = actions.loadRanking()

    fun newSongs() = actions.loadNewSongs()

    fun artistSongs(artistKey: String) = actions.loadArtistSongs(artistKey, restorePage = 0)

    fun languages() = actions.loadLanguages()

    fun languageSongs(language: String) = actions.loadLanguageSongs(language, restorePage = 0)

    fun tags() = actions.loadTags()

    fun prefetchTags() = actions.prefetchTags()

    fun tagSongs(tag: String) = actions.loadTagSongs(tag, restorePage = 0)
}
