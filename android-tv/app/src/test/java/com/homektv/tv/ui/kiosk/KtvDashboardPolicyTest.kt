package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvDashboardPolicyTest {

    @Test
    fun testInitialStateIsDashboard() {
        val policy = KtvDashboardPolicy()
        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
    }

    @Test
    fun testNavigateToTileSwitchesModeAndRecordsBackstack() {
        val policy = KtvDashboardPolicy()
        policy.onTileClicked(KtvDashboardTile.PINYIN)
        assertEquals(KtvKioskViewMode.PINYIN_SEARCH, policy.currentMode)
        assertTrue(policy.canHandleBack())

        val handled = policy.handleBack()
        assertTrue(handled)
        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
        assertFalse(policy.canHandleBack())
    }

    @Test
    fun testTilesDefinitionContainsEightCommercialKtvEntries() {
        val tiles = KtvDashboardTile.values()
        assertEquals(8, tiles.size)
        assertTrue(tiles.contains(KtvDashboardTile.PINYIN))
        assertTrue(tiles.contains(KtvDashboardTile.SINGER))
        assertTrue(tiles.contains(KtvDashboardTile.CATEGORY))
        assertTrue(tiles.contains(KtvDashboardTile.LANGUAGE))
        assertTrue(tiles.contains(KtvDashboardTile.RANKING))
        assertTrue(tiles.contains(KtvDashboardTile.FAVORITES))
        assertTrue(tiles.contains(KtvDashboardTile.HISTORY))
        assertTrue(tiles.contains(KtvDashboardTile.ORDERED_QUEUE))
    }

    @Test
    fun testFavoritesAndHistoryTileNavigationAndBackstack() {
        val policy = KtvDashboardPolicy()

        policy.onTileClicked(KtvDashboardTile.FAVORITES)
        assertEquals(KtvKioskViewMode.FAVORITES_LIST, policy.currentMode)
        assertEquals(KtvDashboardTile.FAVORITES, policy.lastActiveTile)
        assertTrue(policy.canHandleBack())
        assertTrue(policy.handleBack())
        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)

        policy.onTileClicked(KtvDashboardTile.HISTORY)
        assertEquals(KtvKioskViewMode.HISTORY_LIST, policy.currentMode)
        assertEquals(KtvDashboardTile.HISTORY, policy.lastActiveTile)
        assertTrue(policy.canHandleBack())
        assertTrue(policy.handleBack())
        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
    }

    @Test
    fun testFocusMemoryRecordsLastTileAndRestoresCorrectly() {
        val policy = KtvDashboardPolicy()
        policy.onTileClicked(KtvDashboardTile.ORDERED_QUEUE)
        assertEquals(KtvDashboardTile.ORDERED_QUEUE, policy.lastActiveTile)

        policy.handleBack()
        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
        assertEquals(KtvDashboardTile.ORDERED_QUEUE, policy.lastActiveTile)
    }

    @Test
    fun legacyRankingTileFallsBackToVisibleSearchEntry() {
        val policy = KtvDashboardPolicy()

        policy.onTileClicked(KtvDashboardTile.RANKING)

        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
        assertEquals(KtvDashboardTile.PINYIN, policy.lastActiveTile)
        assertEquals("搜索点歌", KtvDashboardTile.PINYIN.title)
    }

    @Test
    fun queueTileOpensDrawerWithoutCreatingAnotherPageRoute() {
        val policy = KtvDashboardPolicy()

        policy.onTileClicked(KtvDashboardTile.ORDERED_QUEUE)

        assertEquals(KtvKioskViewMode.DASHBOARD, policy.currentMode)
        assertEquals(KtvDashboardTile.ORDERED_QUEUE, policy.lastActiveTile)
        assertFalse(policy.canHandleBack())
    }

    @Test
    fun visibleHomeEntriesExcludeLegacyRanking() {
        assertEquals(
            listOf(
                KtvDashboardTile.PINYIN,
                KtvDashboardTile.SINGER,
                KtvDashboardTile.CATEGORY,
                KtvDashboardTile.LANGUAGE,
                KtvDashboardTile.FAVORITES,
                KtvDashboardTile.HISTORY,
                KtvDashboardTile.ORDERED_QUEUE,
            ),
            KtvDashboardTile.dashboardEntries,
        )
    }
}
