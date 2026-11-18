package com.homektv.tv.ui

import com.homektv.tv.ui.kiosk.KtvGlobalSearchPolicy
import java.io.File
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
            KioskTab.SINGERS,
            KtvGlobalSearchPolicy.targetTabForQuery(KioskTab.SINGERS, enabled = true),
        )
        assertEquals(
            KioskTab.PINYIN,
            KtvGlobalSearchPolicy.targetTabForQuery(KioskTab.CATEGORIES, enabled = true),
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

    @Test
    fun resolveSearchScopePrioritizesSelectedArtistEvenUnderPinyinTab() {
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardSearchScope.ARTIST_SONGS,
            KtvGlobalSearchPolicy.resolveSearchScope(KioskTab.PINYIN, "国风堂"),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardSearchScope.ARTIST_SONGS,
            KtvGlobalSearchPolicy.resolveSearchScope(KioskTab.SINGERS, "国风堂"),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardSearchScope.SINGER_DIRECTORY,
            KtvGlobalSearchPolicy.resolveSearchScope(KioskTab.SINGERS, null),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardSearchScope.GLOBAL_SONGS,
            KtvGlobalSearchPolicy.resolveSearchScope(KioskTab.PINYIN, null),
        )
        assertEquals(
            KtvGlobalSearchPolicy.KeyboardSearchScope.GLOBAL_SONGS,
            KtvGlobalSearchPolicy.resolveSearchScope(KioskTab.CATEGORIES, ""),
        )
    }

    @Test
    fun controllerRoutesArtistSongSearchBeforeGlobalSearchAndProtectsRankingReload() {
        val controller = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val onKeywordInput = controller
            .substringAfter("private fun onKeywordInput(keyword: String)")
            .substringBefore("fun refreshGlobalSearchPreference()")
        assertTrue(onKeywordInput.contains("KtvGlobalSearchPolicy.resolveSearchScope(currentTab, selectedArtist)"))
        assertTrue(onKeywordInput.contains("KeyboardSearchScope.ARTIST_SONGS ->"))
        assertTrue(onKeywordInput.contains("catalogActionRouter.searchArtistSongs(artistKey, normalizedKeyword)"))

        val applyTab = controller
            .substringAfter("private fun applyTabSelection(tab: KioskTab)")
            .substringBefore("private fun onKeywordInput(keyword: String)")
        val pinyinBranch = applyTab
            .substringAfter("KioskTab.PINYIN -> {")
            .substringBefore("KioskTab.SINGERS ->")
        assertTrue(pinyinBranch.contains("if (presentationState.selectedArtist.value == null)"))
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val localCandidate = File(current, relativePath)
            if (localCandidate.isFile) return localCandidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
