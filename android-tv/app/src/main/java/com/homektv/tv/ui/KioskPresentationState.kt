package com.homektv.tv.ui

import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.SongDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class KioskTab {
    DASHBOARD,
    PINYIN,
    SINGERS,
    RANKINGS,
    CATEGORIES,
    FAVORITES,
    PLAYLISTS,
    HISTORY,
}

/** User-visible commercial categories; the data tab may still be a shared Home KTV query family. */
enum class KioskCommercialRoute {
    SONG_NAME,
    HIGH_DEFINITION,
    SINGERS,
    HOT_NEW,
    FEATURED,
    LOCAL,
    FAVORITES,
    LANGUAGES,
    THEMES,
    VARIETY,
    TIKTOK,
}

/**
 * 点歌台视图状态机，解耦 UI 渲染与网络/数据源。
 */
class KioskPresentationState(initialTab: KioskTab = KioskTab.PINYIN) {

    private val _currentTab = MutableStateFlow(visibleTabOrDashboard(initialTab))
    val currentTab: StateFlow<KioskTab> = _currentTab.asStateFlow()

    private val _commercialRoute = MutableStateFlow<KioskCommercialRoute?>(defaultCommercialRoute(initialTab))
    val commercialRoute: StateFlow<KioskCommercialRoute?> = _commercialRoute.asStateFlow()

    private val _selectedArtist = MutableStateFlow<String?>(null)
    val selectedArtist: StateFlow<String?> = _selectedArtist.asStateFlow()

    private val _isVocalToggleEnabled = MutableStateFlow(false)
    val isVocalToggleEnabled: StateFlow<Boolean> = _isVocalToggleEnabled.asStateFlow()

    private val _rankings = MutableStateFlow<List<SongDto>>(emptyList())
    val rankings: StateFlow<List<SongDto>> = _rankings.asStateFlow()

    private val _newSongs = MutableStateFlow<List<SongDto>>(emptyList())
    val newSongs: StateFlow<List<SongDto>> = _newSongs.asStateFlow()

    fun selectTab(tab: KioskTab) {
        selectTab(tab, defaultCommercialRoute(tab))
    }

    /**
     * Publishes route identity before the UI-observed tab change, so tab collectors
     * never render a new content page with the previous commercial capsule selected.
     */
    fun selectTab(tab: KioskTab, route: KioskCommercialRoute?) {
        _commercialRoute.value = route
        _currentTab.value = visibleTabOrDashboard(tab)
    }

    fun selectCommercialRoute(route: KioskCommercialRoute) {
        _commercialRoute.value = route
    }

    fun selectArtist(artistName: String) {
        _selectedArtist.value = artistName
        _currentTab.value = KioskTab.PINYIN
    }

    fun clearArtistSelection() {
        _selectedArtist.value = null
    }

    fun updateRankings(songs: List<SongDto>) {
        _rankings.value = songs
    }

    fun updateNewSongs(songs: List<SongDto>) {
        _newSongs.value = songs
    }

    fun updateAudioLayout(layout: AudioLayout, audioTracks: Int = 1) {
        _isVocalToggleEnabled.value = VocalTogglePolicy.resolve(layout.layout, audioTracks).isEnabled
    }

    private fun visibleTabOrDashboard(tab: KioskTab): KioskTab = tab

    private fun defaultCommercialRoute(tab: KioskTab): KioskCommercialRoute? = when (tab) {
        KioskTab.PINYIN -> KioskCommercialRoute.SONG_NAME
        KioskTab.SINGERS -> KioskCommercialRoute.SINGERS
        KioskTab.RANKINGS -> KioskCommercialRoute.HOT_NEW
        KioskTab.FAVORITES -> KioskCommercialRoute.FAVORITES
        KioskTab.PLAYLISTS -> KioskCommercialRoute.FEATURED
        KioskTab.DASHBOARD,
        KioskTab.CATEGORIES,
        KioskTab.HISTORY -> null
    }
}
