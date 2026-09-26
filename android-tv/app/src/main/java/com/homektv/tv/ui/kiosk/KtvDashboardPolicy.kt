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
        val selectedTile = KtvDashboardTile.restoreDashboardFocus(tile)
        lastActiveTile = selectedTile
        if (tile == KtvDashboardTile.RANKING || selectedTile == KtvDashboardTile.ORDERED_QUEUE) {
            // Legacy rankings return home; the queue opens a drawer and is not a page route.
            currentMode = KtvKioskViewMode.DASHBOARD
            return
        }
        currentMode = when (selectedTile) {
            KtvDashboardTile.PINYIN -> KtvKioskViewMode.PINYIN_SEARCH
            KtvDashboardTile.SINGER -> KtvKioskViewMode.SINGER_CATALOG
            KtvDashboardTile.CATEGORY -> KtvKioskViewMode.CATEGORY_LIST
            KtvDashboardTile.LANGUAGE -> KtvKioskViewMode.LANGUAGE_LIST
            KtvDashboardTile.RANKING -> KtvKioskViewMode.DASHBOARD
            KtvDashboardTile.FAVORITES -> KtvKioskViewMode.FAVORITES_LIST
            KtvDashboardTile.HISTORY -> KtvKioskViewMode.HISTORY_LIST
            KtvDashboardTile.ORDERED_QUEUE -> KtvKioskViewMode.DASHBOARD
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
