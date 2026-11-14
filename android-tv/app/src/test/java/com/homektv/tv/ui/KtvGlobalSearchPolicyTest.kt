package com.homektv.tv.ui

import com.homektv.tv.ui.kiosk.KtvGlobalSearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvGlobalSearchPolicyTest {
    @Test
    fun enabledPreferenceShowsGlobalKeyboardAcrossNonVarietyTabs() {
        assertTrue(KtvGlobalSearchPolicy.shouldShowKeyboard(KioskTab.PINYIN, false, true))
        listOf(
            KioskTab.SINGERS,
            KioskTab.RANKINGS,
            KioskTab.CATEGORIES,
            KioskTab.FAVORITES,
            KioskTab.PLAYLISTS,
            KioskTab.HISTORY,
        ).forEach { tab -> assertTrue("$tab should expose global search", KtvGlobalSearchPolicy.shouldShowKeyboard(tab, false, true)) }
        assertFalse(KtvGlobalSearchPolicy.shouldShowKeyboard(KioskTab.CATEGORIES, true, true))
    }

    @Test
    fun disabledPreferenceKeepsSongNameSearchAndHidesCrossTabKeyboard() {
        assertTrue(KtvGlobalSearchPolicy.shouldShowKeyboard(KioskTab.PINYIN, false, false))
        assertFalse(KtvGlobalSearchPolicy.shouldShowKeyboard(KioskTab.SINGERS, false, false))
        assertFalse(KtvGlobalSearchPolicy.shouldShowKeyboard(KioskTab.CATEGORIES, false, false))
        assertEquals(
            KioskTab.PINYIN,
            KtvGlobalSearchPolicy.targetTabForQuery(KioskTab.SINGERS, enabled = true),
        )
        assertEquals(
            KioskTab.SINGERS,
            KtvGlobalSearchPolicy.targetTabForQuery(KioskTab.SINGERS, enabled = false),
        )
        assertEquals(
            KioskTab.PINYIN,
            KtvGlobalSearchPolicy.targetTabForQuery(KioskTab.PINYIN, enabled = false),
        )
    }

    @Test
    fun keyboardReturnsToVisibleSingerResultsOrEmptyState() {
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardContentTarget.SINGER_LIST,
            KtvGlobalSearchPolicy.keyboardContentTarget(
                tab = KioskTab.SINGERS,
                singerListVisible = true,
                songResultsVisible = false,
            ),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardContentTarget.SONG_RESULTS,
            KtvGlobalSearchPolicy.keyboardContentTarget(
                tab = KioskTab.SINGERS,
                singerListVisible = false,
                songResultsVisible = true,
            ),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardContentTarget.EMPTY_STATE,
            KtvGlobalSearchPolicy.keyboardContentTarget(
                tab = KioskTab.FAVORITES,
                singerListVisible = false,
                songResultsVisible = false,
            ),
        )
    }

    @Test
    fun keyboardVisibilityReconcilesAcrossQueueDrawerAndHiddenTabs() {
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.VISIBLE,
            KtvGlobalSearchPolicy.resolveKeyboardVisibility(shouldShowKeyboard = true, isQueueDrawerOpen = false),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.INVISIBLE,
            KtvGlobalSearchPolicy.resolveKeyboardVisibility(shouldShowKeyboard = true, isQueueDrawerOpen = true),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.GONE,
            KtvGlobalSearchPolicy.resolveKeyboardVisibility(shouldShowKeyboard = false, isQueueDrawerOpen = true),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.GONE,
            KtvGlobalSearchPolicy.resolveKeyboardVisibility(shouldShowKeyboard = false, isQueueDrawerOpen = false),
        )
    }
}
