package com.homektv.tv.controller

/** Catalog operations shared by the phone/tablet controller and the TV kiosk. */
interface ControllerCatalogActions {
    fun setQuery(value: String)
    fun loadMoreSearch()
    fun loadRanking()
    fun loadNewSongs()
    fun loadArtists(gender: String, initial: String, restorePage: Int, region: String = "")
    fun searchArtists(gender: String, initial: String, region: String = "", keyword: String) {
        loadArtists(gender, initial, 0, region)
    }
    fun restoreArtistsSearch(
        gender: String,
        initial: String,
        region: String = "",
        keyword: String,
        restorePage: Int = 0,
    ) {
        searchArtists(gender, initial, region, keyword)
    }
    fun loadArtistSongs(artistKey: String, restorePage: Int)
    fun searchArtistSongs(artistKey: String, keyword: String) {
        loadArtistSongs(artistKey, 0)
    }
    fun restoreArtistSongsSearch(artistKey: String, keyword: String, restorePage: Int = 0) {
        searchArtistSongs(artistKey, keyword)
    }
    fun restoreSearch(query: String, restorePage: Int = 0) {
        setQuery(query)
    }
    fun loadLanguageSongs(language: String, restorePage: Int)
    fun loadTagSongs(tag: String, restorePage: Int)
    fun loadLanguages()
    fun loadTags()
    fun prefetchTags() {}
    fun loadMoreCatalog()
    fun loadKioskAllSongs()
    fun restoreKioskAllSongs(restorePage: Int = 0) {
        loadKioskAllSongs()
    }
    fun loadMoreKioskAllSongs()
    fun stopKioskAllSongs()
}

/** Optional lifecycle seam used by kiosk hosts to bound catalog polling. */
interface ControllerCatalogVisibility {
    fun setCatalogVisible(visible: Boolean)
}
