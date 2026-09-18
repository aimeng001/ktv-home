package com.homektv.tv.ui.kiosk

enum class KtvKioskViewMode {
    DASHBOARD,
    PINYIN_SEARCH,
    SINGER_CATALOG,
    CATEGORY_LIST,
    LANGUAGE_LIST,
    RANKING_LIST,
    FAVORITES_LIST,
    HISTORY_LIST,
    ORDERED_QUEUE_DRAWER,
}

class KtvDashboardPolicy {
    var currentMode: KtvKioskViewMode = KtvKioskViewMode.DASHBOARD
        private set
    var lastActiveTile: KtvDashboardTile = KtvDashboardTile.PINYIN
        private set

    fun onTileClicked(tile: KtvDashboardTile) {
        lastActiveTile = tile
        currentMode = when (tile) {
            KtvDashboardTile.PINYIN -> KtvKioskViewMode.PINYIN_SEARCH
            KtvDashboardTile.SINGER -> KtvKioskViewMode.SINGER_CATALOG
            KtvDashboardTile.CATEGORY -> KtvKioskViewMode.CATEGORY_LIST
            KtvDashboardTile.LANGUAGE -> KtvKioskViewMode.LANGUAGE_LIST
            KtvDashboardTile.RANKING -> KtvKioskViewMode.RANKING_LIST
            KtvDashboardTile.FAVORITES -> KtvKioskViewMode.FAVORITES_LIST
            KtvDashboardTile.HISTORY -> KtvKioskViewMode.HISTORY_LIST
            KtvDashboardTile.ORDERED_QUEUE -> KtvKioskViewMode.ORDERED_QUEUE_DRAWER
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
