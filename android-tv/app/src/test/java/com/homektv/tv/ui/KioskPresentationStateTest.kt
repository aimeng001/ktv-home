package com.homektv.tv.ui

import com.homektv.tv.net.AudioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPresentationStateTest {

    @Test
    fun initialTab_isPinyin() {
        val state = KioskPresentationState()
        assertEquals(KioskTab.PINYIN, state.currentTab.value)
    }

    @Test
    fun selectTab_changesActiveTab() {
        val state = KioskPresentationState()
        state.selectTab(KioskTab.SINGERS)
        assertEquals(KioskTab.SINGERS, state.currentTab.value)

        state.selectTab(KioskTab.RANKINGS)
        assertEquals(KioskTab.DASHBOARD, state.currentTab.value)

        state.selectTab(KioskTab.PLAYLISTS)
        assertEquals(KioskTab.DASHBOARD, state.currentTab.value)

        state.selectTab(KioskTab.CATEGORIES)
        assertEquals(KioskTab.CATEGORIES, state.currentTab.value)
    }

    @Test
    fun constructorRestoresHiddenLegacyTabsToDashboard() {
        assertEquals(KioskTab.DASHBOARD, KioskPresentationState(KioskTab.RANKINGS).currentTab.value)
        assertEquals(KioskTab.DASHBOARD, KioskPresentationState(KioskTab.PLAYLISTS).currentTab.value)
    }

    @Test
    fun selectArtist_switchesToPinyinWithArtistTitle() {
        val state = KioskPresentationState()
        state.selectTab(KioskTab.SINGERS)
        state.selectArtist("周杰伦")
        assertEquals(KioskTab.PINYIN, state.currentTab.value)
        assertEquals("周杰伦", state.selectedArtist.value)
    }

    @Test
    fun clearArtistSelection_resetsSelectedArtist() {
        val state = KioskPresentationState()
        state.selectArtist("陈奕迅")
        assertEquals("陈奕迅", state.selectedArtist.value)
        state.clearArtistSelection()
        assertEquals(null, state.selectedArtist.value)
    }

    @Test
    fun vocalPolicy_normalStereoDisablesToggle() {
        val state = KioskPresentationState()
        state.updateAudioLayout(AudioLayout.normalStereo())
        assertFalse(state.isVocalToggleEnabled.value)
    }

    @Test
    fun vocalPolicy_dualTrackEnablesToggle() {
        val state = KioskPresentationState()
        state.updateAudioLayout(AudioLayout(layout = "DUAL_TRACK"))
        assertTrue(state.isVocalToggleEnabled.value)
    }
}
