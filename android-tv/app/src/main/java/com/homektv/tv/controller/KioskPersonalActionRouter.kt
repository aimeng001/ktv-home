package com.homektv.tv.controller

import com.homektv.tv.net.SongDto

/** Keeps TV personal pages on the same identity-scoped controller operations. */
class KioskPersonalActionRouter(
    private val actions: ControllerPersonalActions,
) {
    fun favorites() = actions.loadFavorites()

    fun toggleFavorite(song: SongDto) = actions.toggleFavorite(song)

    fun playlists() = actions.loadPlaylists()

    fun playlistDetail(id: Long) = actions.loadPlaylistDetail(id)

    fun clearPlaylistDetail() = actions.clearPlaylistDetail()

    fun orderPlaylist(id: Long) = actions.orderPlaylist(id)

    fun history() = actions.loadHistory(mine = false)

    fun repeatHistory(id: Long) = actions.repeatHistory(id)
}
