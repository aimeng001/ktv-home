package com.homektv.tv.controller

import com.homektv.tv.net.SongDto

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskPersonalActionRouterTest {

    @Test
    fun routesFavoritesPlaylistsAndHistoryToController() {
        val fake = RecordingPersonalActions()
        val router = KioskPersonalActionRouter(fake)

        router.favorites()
        router.toggleFavorite(SongDto(id = 9L))
        router.playlists()
        router.playlistDetail(4L)
        router.orderPlaylist(4L)
        router.history()
        router.repeatHistory(7L)

        assertEquals(
            listOf(
                "favorites",
                "favorite-toggle:9",
                "playlists",
                "playlist-detail:4",
                "playlist-order:4",
                "history",
                "history-repeat:7",
            ),
            fake.calls,
        )
    }

    private class RecordingPersonalActions : ControllerPersonalActions {
        val calls = mutableListOf<String>()

        override fun loadFavorites() {
            calls += "favorites"
        }

        override fun toggleFavorite(song: SongDto) {
            calls += "favorite-toggle:${song.id}"
        }

        override fun loadPlaylists() {
            calls += "playlists"
        }

        override fun loadPlaylistDetail(playlistId: Long) {
            calls += "playlist-detail:$playlistId"
        }

        override fun clearPlaylistDetail() = Unit

        override fun orderPlaylist(playlistId: Long) {
            calls += "playlist-order:$playlistId"
        }

        override fun loadHistory(mine: Boolean) {
            calls += "history"
        }

        override fun repeatHistory(historyId: Long) {
            calls += "history-repeat:$historyId"
        }
    }
}
