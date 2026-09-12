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

    fun artists() = actions.loadArtists(gender = "", initial = "", restorePage = 0)

    fun rankings() = actions.loadRanking()

    fun newSongs() = actions.loadNewSongs()

    fun artistSongs(artistKey: String) = actions.loadArtistSongs(artistKey, restorePage = 0)

    fun languages() = actions.loadLanguages()

    fun languageSongs(language: String) = actions.loadLanguageSongs(language, restorePage = 0)

    fun tags() = actions.loadTags()

    fun tagSongs(tag: String) = actions.loadTagSongs(tag, restorePage = 0)
}
