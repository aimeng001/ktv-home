package com.homektv.tv.ui

import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvTabSeparationTest {

    @Test
    fun tabState_preservesLegacyDatasetsWhileHiddenRoutesReturnHome() {
        val state = KioskPresentationState()

        val rankingSongs = listOf(
            SongDto(id = 1L, title = "海阔天空", artist = "Beyond"),
            SongDto(id = 2L, title = "光辉岁月", artist = "Beyond"),
        )
        val categorySongs = listOf(
            SongDto(id = 10L, title = "反方向的钟", artist = "周杰伦"),
            SongDto(id = 11L, title = "夜曲", artist = "周杰伦"),
        )

        state.updateRankings(rankingSongs)
        state.updateNewSongs(categorySongs)

        assertEquals(2, state.rankings.value.size)
        assertEquals(2, state.newSongs.value.size)
        assertEquals("海阔天空", state.rankings.value.first().title)
        assertEquals("反方向的钟", state.newSongs.value.first().title)
        assertNotEquals(state.rankings.value, state.newSongs.value)

        state.selectTab(KioskTab.RANKINGS)
        assertEquals(KioskTab.DASHBOARD, state.currentTab.value)
        assertEquals(2, state.rankings.value.size)

        state.selectTab(KioskTab.CATEGORIES)
        assertEquals(KioskTab.CATEGORIES, state.currentTab.value)
        assertEquals(2, state.newSongs.value.size)

        // Legacy ranking state remains stored but is no longer a visible navigation destination.
        state.selectTab(KioskTab.RANKINGS)
        assertEquals(KioskTab.DASHBOARD, state.currentTab.value)
        assertEquals("海阔天空", state.rankings.value.first().title)
    }

    @Test
    fun selectArtist_switchesToPinyinTabAndPreservesArtistName() {
        val state = KioskPresentationState()
        state.selectArtist("周杰伦")

        assertEquals(KioskTab.PINYIN, state.currentTab.value)
        assertEquals("周杰伦", state.selectedArtist.value)

        state.clearArtistSelection()
        assertTrue(state.selectedArtist.value == null)
    }
}
