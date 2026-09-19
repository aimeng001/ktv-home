package com.homektv.tv.controller

/** Catalog operations shared by the phone/tablet controller and the TV kiosk. */
interface ControllerCatalogActions {
    fun setQuery(value: String)
    fun loadMoreSearch()
    fun loadRanking()
    fun loadNewSongs()
    fun loadArtists(gender: String, initial: String, restorePage: Int)
    fun loadArtistSongs(artistKey: String, restorePage: Int)
    fun loadLanguageSongs(language: String, restorePage: Int)
    fun loadTagSongs(tag: String, restorePage: Int)
    fun loadLanguages()
    fun loadTags()
    fun loadMoreCatalog()
}

/** Optional lifecycle seam used by kiosk hosts to bound catalog polling. */
interface ControllerCatalogVisibility {
    fun setCatalogVisible(visible: Boolean)
}
