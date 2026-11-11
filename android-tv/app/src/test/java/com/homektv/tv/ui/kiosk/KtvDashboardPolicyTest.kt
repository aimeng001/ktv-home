package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardPolicyTest {
    @Test
    fun initialStateRestoresFocusToTheFirstVisibleTile() {
        val policy = KtvDashboardPolicy()

        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
        assertEquals(KtvDashboardTile.HOT_RANKING, policy.lastActiveTile)
        assertEquals(KtvDashboardTile.HOT_RANKING, KtvDashboardTile.restoreDashboardFocus(null))
    }

    @Test
    fun tenCommercialTilesAreTheCompleteDashboardEntrySet() {
        assertEquals(10, KtvDashboardTile.entries.size)
        assertEquals(KtvDashboardTile.entries.toList(), KtvDashboardTile.dashboardEntries)
        assertTrue(KtvDashboardTile.dashboardEntries.all { it.artworkRes != 0 })
    }

    @Test
    fun tileRoutesMatchTheExistingKioskPages() {
        val expectedModes = mapOf(
            KtvDashboardTile.HOT_RANKING to KtvKioskViewMode.RANKING_LIST,
            KtvDashboardTile.NEW_SONGS to KtvKioskViewMode.PLAYLISTS_LIST,
            KtvDashboardTile.SINGER to KtvKioskViewMode.SINGER_CATALOG,
            KtvDashboardTile.SONG_NAME to KtvKioskViewMode.PINYIN_SEARCH,
            KtvDashboardTile.TIKTOK to KtvKioskViewMode.CATEGORY_LIST,
            KtvDashboardTile.LANGUAGE to KtvKioskViewMode.LANGUAGE_LIST,
            KtvDashboardTile.THEME to KtvKioskViewMode.CATEGORY_LIST,
            KtvDashboardTile.OPERA to KtvKioskViewMode.CATEGORY_LIST,
            KtvDashboardTile.FAVORITES to KtvKioskViewMode.FAVORITES_LIST,
            KtvDashboardTile.LOCAL to KtvKioskViewMode.PINYIN_SEARCH,
        )

        expectedModes.forEach { (tile, mode) ->
            val policy = KtvDashboardPolicy()
            policy.onTileClicked(tile)
            assertEquals("$tile should use its existing page route", mode, policy.currentMode)
            assertEquals(tile, policy.lastActiveTile)
            assertTrue(policy.canHandleBack())
            assertTrue(policy.handleBack())
            assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
            assertFalse(policy.canHandleBack())
        }
    }
}
