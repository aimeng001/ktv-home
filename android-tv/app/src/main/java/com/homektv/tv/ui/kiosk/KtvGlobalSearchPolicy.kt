package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.KioskTab

/** Pure routing and focus decisions for the optional cross-tab search keyboard. */
object KtvGlobalSearchPolicy {
    enum class KeyboardContentTarget {
        SONG_RESULTS,
        SINGER_LIST,
        EMPTY_STATE,
    }

    enum class KeyboardVisibilityMode {
        GONE,
        INVISIBLE,
        VISIBLE,
    }

    fun shouldShowKeyboard(tab: KioskTab, isVariety: Boolean, enabled: Boolean): Boolean =
        !isVariety && (tab == KioskTab.PINYIN || enabled)

    fun resolveKeyboardVisibility(
        shouldShowKeyboard: Boolean,
        isQueueDrawerOpen: Boolean,
    ): KeyboardVisibilityMode = when {
        !shouldShowKeyboard -> KeyboardVisibilityMode.GONE
        isQueueDrawerOpen -> KeyboardVisibilityMode.INVISIBLE
        else -> KeyboardVisibilityMode.VISIBLE
    }

    fun targetTabForQuery(currentTab: KioskTab, enabled: Boolean): KioskTab =
        if (currentTab == KioskTab.PINYIN || !enabled) currentTab else KioskTab.PINYIN

    fun keyboardContentTarget(
        tab: KioskTab,
        singerListVisible: Boolean,
        songResultsVisible: Boolean,
    ): KeyboardContentTarget = when {
        tab == KioskTab.SINGERS && singerListVisible -> KeyboardContentTarget.SINGER_LIST
        songResultsVisible -> KeyboardContentTarget.SONG_RESULTS
        else -> KeyboardContentTarget.EMPTY_STATE
    }
}
