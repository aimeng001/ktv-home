package com.homektv.tv.ui.kiosk

enum class KtvKioskViewMode {
    DASHBOARD,
    PINYIN_SEARCH,
    SINGER_CATALOG,
    CATEGORY_LIST,
    LANGUAGE_LIST,
    RANKING_LIST,
    PLAYLISTS_LIST,
    FAVORITES_LIST,
    HISTORY_LIST,
    ORDERED_QUEUE_DRAWER,
}

class KtvDashboardPolicy {
    var currentMode: KtvKioskViewMode = KtvKioskViewMode.DASHBOARD
        private set
    var lastActiveTile: KtvDashboardTile = KtvDashboardTile.HOT_RANKING
        private set

    fun onTileClicked(tile: KtvDashboardTile) {
        val selectedTile = KtvDashboardTile.restoreDashboardFocus(tile)
        lastActiveTile = selectedTile
        currentMode = when (selectedTile) {
            KtvDashboardTile.HOT_RANKING -> KtvKioskViewMode.RANKING_LIST
            KtvDashboardTile.NEW_SONGS -> KtvKioskViewMode.PLAYLISTS_LIST
            KtvDashboardTile.SINGER -> KtvKioskViewMode.SINGER_CATALOG
            KtvDashboardTile.SONG_NAME, KtvDashboardTile.LOCAL -> KtvKioskViewMode.PINYIN_SEARCH
            KtvDashboardTile.TIKTOK -> KtvKioskViewMode.CATEGORY_LIST
            KtvDashboardTile.THEME, KtvDashboardTile.OPERA -> KtvKioskViewMode.CATEGORY_LIST
            KtvDashboardTile.LANGUAGE -> KtvKioskViewMode.LANGUAGE_LIST
            KtvDashboardTile.FAVORITES -> KtvKioskViewMode.FAVORITES_LIST
        }
    }

    fun canHandleBack(): Boolean = currentMode != KtvKioskViewMode.DASHBOARD

    fun handleBack(): Boolean {
        return if (currentMode != KtvKioskViewMode.DASHBOARD) {
            currentMode = KtvKioskViewMode.DASHBOARD
            true
        } else {
            false
        }
    }
}
