package com.homektv.tv.ui.controller

import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.NamedCount
import com.homektv.tv.net.PlaylistSummary
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.RecentHistoryItem
import com.homektv.tv.net.SongDto

/** Rows rendered by every controller panel. IDs remain stable across updates. */
internal sealed interface PanelRow {
    val stableId: Long
}

internal data class SongPanelRow(
    val song: SongDto,
    val orderPending: Boolean,
    val favorite: Boolean,
    val favoritePending: Boolean,
    val queueState: com.homektv.tv.ui.SongQueueState? = null,
) : PanelRow {
    override val stableId: Long = song.id
}

internal data class ArtistPanelRow(
    val artist: ArtistItem,
    val action: () -> Unit,
) : PanelRow {
    override val stableId: Long = StableIdPolicy.hash64(-1_000_000_000L, artist.artistKey)
}

internal data class NamedCountPanelRow(
    val item: NamedCount,
    val action: () -> Unit,
) : PanelRow {
    override val stableId: Long = StableIdPolicy.hash64(-2_000_000_000L, item.name)
}

internal data class PlaylistPanelRow(
    val playlist: PlaylistSummary,
    val orderPending: Boolean,
) : PanelRow {
    override val stableId: Long = -3_000_000_000L + playlist.id
}

internal data class HistoryPanelRow(
    val item: RecentHistoryItem,
    val repeatPending: Boolean,
) : PanelRow {
    override val stableId: Long = -4_000_000_000L + item.historyId
}

internal data class QueuePanelRow(
    val index: Int,
    val entry: QueueEntry,
    val topPending: Boolean,
    val cancelPending: Boolean,
    val canManage: Boolean,
) : PanelRow {
    override val stableId: Long = entry.queueId ?: (Long.MIN_VALUE + index)
}

internal data class MessagePanelRow(val id: Long, val text: String) : PanelRow {
    constructor(text: String) : this(-10L, text)
    override val stableId: Long = id
}

internal object PanelRowViewType {
    const val SONG = 1
    const val ARTIST = 2
    const val NAMED_COUNT = 3
    const val PLAYLIST = 4
    const val HISTORY = 5
    const val QUEUE = 6
    const val MESSAGE = 7
    const val ACTION = 8

    fun from(row: PanelRow): Int = when (row) {
        is SongPanelRow -> SONG
        is ArtistPanelRow -> ARTIST
        is NamedCountPanelRow -> NAMED_COUNT
        is PlaylistPanelRow -> PLAYLIST
        is HistoryPanelRow -> HISTORY
        is QueuePanelRow -> QUEUE
        is MessagePanelRow -> MESSAGE
        is ActionPanelRow -> ACTION
    }
}

internal data class ActionPanelRow(
    val id: Long,
    val text: String,
    val enabled: Boolean,
    val contentDescription: String,
    val actionKey: String = "",
    val action: () -> Unit,
) : PanelRow {
    override val stableId: Long = id
}
