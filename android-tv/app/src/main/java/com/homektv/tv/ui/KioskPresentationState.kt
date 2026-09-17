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

/**
 * 点歌台视图状态机，解耦 UI 渲染与网络/数据源。
 */
class KioskPresentationState(initialTab: KioskTab = KioskTab.PINYIN) {

    private val _currentTab = MutableStateFlow(initialTab)
    val currentTab: StateFlow<KioskTab> = _currentTab.asStateFlow()

    private val _selectedArtist = MutableStateFlow<String?>(null)
    val selectedArtist: StateFlow<String?> = _selectedArtist.asStateFlow()

    private val _isVocalToggleEnabled = MutableStateFlow(false)
    val isVocalToggleEnabled: StateFlow<Boolean> = _isVocalToggleEnabled.asStateFlow()

    private val _rankings = MutableStateFlow<List<SongDto>>(emptyList())
    val rankings: StateFlow<List<SongDto>> = _rankings.asStateFlow()

    private val _newSongs = MutableStateFlow<List<SongDto>>(emptyList())
    val newSongs: StateFlow<List<SongDto>> = _newSongs.asStateFlow()

    fun selectTab(tab: KioskTab) {
        _currentTab.value = tab
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
}
