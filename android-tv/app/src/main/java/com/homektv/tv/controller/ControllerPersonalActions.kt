package com.homektv.tv.controller

import com.homektv.tv.net.SongDto

/** Personal catalog operations shared by controller surfaces. */
interface ControllerPersonalActions {
    fun loadFavorites()
    fun toggleFavorite(song: SongDto)
    fun loadPlaylists()
    fun loadPlaylistDetail(playlistId: Long)
    fun clearPlaylistDetail()
    fun orderPlaylist(playlistId: Long)
    fun loadHistory(mine: Boolean)
    fun repeatHistory(historyId: Long)
}
