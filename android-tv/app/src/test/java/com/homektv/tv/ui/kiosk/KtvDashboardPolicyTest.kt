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
    fun testTilesDefinitionContainsSixCommercialKtvEntries() {
        val tiles = KtvDashboardTile.values()
        assertEquals(6, tiles.size)
        assertTrue(tiles.contains(KtvDashboardTile.PINYIN))
        assertTrue(tiles.contains(KtvDashboardTile.SINGER))
        assertTrue(tiles.contains(KtvDashboardTile.CATEGORY))
        assertTrue(tiles.contains(KtvDashboardTile.LANGUAGE))
        assertTrue(tiles.contains(KtvDashboardTile.RANKING))
        assertTrue(tiles.contains(KtvDashboardTile.ORDERED_QUEUE))
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
}
