package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.KioskCommercialRoute
import com.homektv.tv.ui.KioskTab

/**
 * 电视端三栏点歌台单帧浏览现场快照。
 *
 * Captures the complete browser view state so navigating deeper, switching to fullscreen playback,
 * or pressing Back restores the exact tab, filters, search query, artist detail, and grid page.
 */
data class KioskNavigationEntry(
    val tab: KioskTab,
    val commercialRoute: KioskCommercialRoute?,
    val selectedArtist: String? = null,
    val selectedArtistKey: String? = null,
    val keyword: String = "",
    val artistGender: String = "",
    val artistInitial: String = "",
    val artistRegion: String = "",
    val categoryMode: KioskCategoryMode = KioskCategoryMode.TAGS,
    val categoryDetail: Boolean = false,
    val categoryValue: String = "",
    val playlistDetail: Boolean = false,
    val playlistId: Long? = null,
    val varietyPanelVisible: Boolean = false,
    val varietyProgram: String? = null,
    val browsingAllSongs: Boolean = false,
    val pageIndex: Int = 0,
    val focusedItemKey: String? = null,
) {
    /** Identifies the logical destination screen ignoring transient focus/page tweaks. */
    fun sameScreen(other: KioskNavigationEntry): Boolean =
        tab == other.tab &&
            commercialRoute == other.commercialRoute &&
            selectedArtist == other.selectedArtist &&
            selectedArtistKey == other.selectedArtistKey &&
            keyword == other.keyword &&
            artistGender == other.artistGender &&
            artistInitial == other.artistInitial &&
            artistRegion == other.artistRegion &&
            categoryMode == other.categoryMode &&
            categoryDetail == other.categoryDetail &&
            categoryValue == other.categoryValue &&
            playlistDetail == other.playlistDetail &&
            playlistId == other.playlistId &&
            varietyPanelVisible == other.varietyPanelVisible &&
            varietyProgram == other.varietyProgram &&
            browsingAllSongs == other.browsingAllSongs

    fun moduleKey(): KioskCommercialRoute? = when {
        selectedArtist != null -> KioskCommercialRoute.SINGERS
        commercialRoute != null -> commercialRoute
        tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.LANGUAGES -> KioskCommercialRoute.LANGUAGES
        tab == KioskTab.CATEGORIES -> KioskCommercialRoute.THEMES
        tab == KioskTab.SINGERS -> KioskCommercialRoute.SINGERS
        tab == KioskTab.RANKINGS -> KioskCommercialRoute.HOT_NEW
        tab == KioskTab.PLAYLISTS -> KioskCommercialRoute.FEATURED
        tab == KioskTab.FAVORITES -> KioskCommercialRoute.FAVORITES
        tab == KioskTab.PINYIN && browsingAllSongs -> KioskCommercialRoute.LOCAL
        tab == KioskTab.PINYIN -> KioskCommercialRoute.SONG_NAME
        else -> null
    }
}

/**
 * Bounded browser navigation stack plus per-module last-state memory.
 */
class KioskNavigationHistory(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private val stack = ArrayDeque<KioskNavigationEntry>()
    private val moduleStates = LinkedHashMap<KioskCommercialRoute, KioskNavigationEntry>()

    fun push(entry: KioskNavigationEntry) {
        if (entry.tab == KioskTab.DASHBOARD && stack.lastOrNull()?.tab == KioskTab.DASHBOARD) {
            stack[stack.lastIndex] = entry
            return
        }
        val top = stack.lastOrNull()
        if (top != null && top.sameScreen(entry)) {
            stack[stack.lastIndex] = entry
        } else {
            stack.addLast(entry)
            while (stack.size > maxDepth) {
                stack.removeFirst()
            }
        }
        rememberModule(entry)
    }

    fun rememberModule(entry: KioskNavigationEntry) {
        if (entry.tab == KioskTab.DASHBOARD) return
        entry.moduleKey()?.let { key ->
            moduleStates[key] = entry
        }
    }

    fun lastModuleState(route: KioskCommercialRoute): KioskNavigationEntry? = moduleStates[route]

    fun pop(): KioskNavigationEntry? = if (stack.isEmpty()) null else stack.removeLast()

    fun peek(): KioskNavigationEntry? = stack.lastOrNull()

    fun canPop(): Boolean = stack.isNotEmpty()

    fun depth(): Int = stack.size

    fun clear() {
        stack.clear()
    }

    fun clearAll() {
        stack.clear()
        moduleStates.clear()
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 32
    }
}
