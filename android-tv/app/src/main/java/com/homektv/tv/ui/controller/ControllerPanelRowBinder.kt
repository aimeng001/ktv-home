package com.homektv.tv.ui.controller

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.SongDto

/** Actions exposed by the Activity without coupling row views to its lifecycle. */
internal interface ControllerPanelActions {
    fun order(song: SongDto)
    fun toggleFavorite(song: SongDto)
    fun orderPlaylist(id: Long)
    fun loadPlaylistDetail(id: Long)
    fun repeatHistory(historyId: Long)
    fun top(queueId: Long)
    fun cancel(queueId: Long, title: String)
    fun confirmDangerous(title: String, message: String, action: () -> Unit)
}

private class SongViewHolderTag(val label: TextView, val orderBtn: Button, val favBtn: Button)
private class PlaylistViewHolderTag(val label: TextView, val orderBtn: Button, val detailBtn: Button)
private class HistoryViewHolderTag(val label: TextView, val repeatBtn: Button)
private class QueueViewHolderTag(val label: TextView, val topBtn: Button, val deleteBtn: Button)

/** Inflates typed row views once per viewType and updates them in place on RecyclerView binds. */
internal class ControllerPanelRowBinder(
    private val actions: ControllerPanelActions,
) {
    fun createView(parent: ViewGroup, viewType: Int): View = when (viewType) {
        PanelRowViewType.SONG -> createSongView(parent)
        PanelRowViewType.ARTIST -> createArtistView(parent)
        PanelRowViewType.NAMED_COUNT -> createNamedCountView(parent)
        PanelRowViewType.PLAYLIST -> createPlaylistView(parent)
        PanelRowViewType.HISTORY -> createHistoryView(parent)
        PanelRowViewType.QUEUE -> createQueueView(parent)
        PanelRowViewType.MESSAGE -> createMessageView(parent)
        PanelRowViewType.ACTION -> createActionView(parent)
        else -> createSongView(parent)
    }

    fun bindView(view: View, row: PanelRow, position: Int) {
        when (row) {
            is SongPanelRow -> bindSong(view, row)
            is ArtistPanelRow -> bindArtist(view, row)
            is NamedCountPanelRow -> bindNamedCount(view, row)
            is PlaylistPanelRow -> bindPlaylist(view, row)
            is HistoryPanelRow -> bindHistory(view, row)
            is QueuePanelRow -> bindQueue(view, row)
            is MessagePanelRow -> bindMessage(view, row)
            is ActionPanelRow -> bindAction(view, row)
        }
    }

    private fun createSongView(parent: ViewGroup): View {
        val host = LinearLayout(parent.context).apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val label = label(host, "", 14f, Color.WHITE, false)
        val orderBtn = button(host, "") {}
        val favBtn = button(host, "") {}
        host.addView(label, weightParams())
        host.addView(orderBtn, wrapParams())
        host.addView(favBtn, wrapParams())
        host.tag = SongViewHolderTag(label, orderBtn, favBtn)
        return host
    }

    private fun bindSong(view: View, row: SongPanelRow) {
        val tag = view.tag as? SongViewHolderTag ?: return
        tag.label.text = "${row.song.title} · ${row.song.artist}"
        val orderText = when {
            !row.song.playable -> "准备中"
            row.orderPending -> "点歌中…"
            row.queueState == com.homektv.tv.ui.SongQueueState.Playing -> "演唱中"
            row.queueState is com.homektv.tv.ui.SongQueueState.Waiting -> "已点"
            else -> "点歌"
        }
        val orderEnabled = row.song.playable && !row.orderPending && row.queueState == null
        tag.orderBtn.text = orderText
        tag.orderBtn.isEnabled = orderEnabled
        tag.orderBtn.contentDescription = "点歌 ${row.song.title} ${row.song.artist}"
        tag.orderBtn.setOnClickListener { actions.order(row.song) }

        tag.favBtn.text = if (row.favorite) "已收藏" else "收藏"
        tag.favBtn.isEnabled = !row.favoritePending
        tag.favBtn.contentDescription = if (row.favorite) "取消收藏 ${row.song.title}" else "收藏 ${row.song.title}"
        tag.favBtn.setOnClickListener { actions.toggleFavorite(row.song) }
    }

    private fun createArtistView(parent: ViewGroup): View =
        button(parent, "") {}.apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }

    private fun bindArtist(view: View, row: ArtistPanelRow) {
        val btn = view as? Button ?: return
        btn.text = "${row.artist.name} · ${row.artist.songCount} 首 · ${row.artist.gender}"
        btn.contentDescription = "查看歌手 ${row.artist.name} 的歌曲"
        btn.setOnClickListener { row.action() }
    }

    private fun createNamedCountView(parent: ViewGroup): View =
        button(parent, "") {}.apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }

    private fun bindNamedCount(view: View, row: NamedCountPanelRow) {
        val btn = view as? Button ?: return
        btn.text = "${row.item.name} · ${row.item.songCount} 首"
        btn.contentDescription = "查看${row.item.name}歌曲"
        btn.setOnClickListener { row.action() }
    }

    private fun createPlaylistView(parent: ViewGroup): View {
        val host = LinearLayout(parent.context).apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val label = label(host, "", 14f, Color.WHITE, false)
        val orderBtn = button(host, "整单点歌") {}
        val detailBtn = button(host, "查看歌曲") {}
        host.addView(label, weightParams())
        host.addView(orderBtn, wrapParams())
        host.addView(detailBtn, wrapParams())
        host.tag = PlaylistViewHolderTag(label, orderBtn, detailBtn)
        return host
    }

    private fun bindPlaylist(view: View, row: PlaylistPanelRow) {
        val tag = view.tag as? PlaylistViewHolderTag ?: return
        tag.label.text = "${row.playlist.name} · ${row.playlist.songCount} 首"
        tag.orderBtn.isEnabled = !row.orderPending
        tag.orderBtn.contentDescription = "整单点歌 ${row.playlist.name}"
        tag.orderBtn.setOnClickListener { actions.orderPlaylist(row.playlist.id) }
        tag.detailBtn.contentDescription = "查看歌单 ${row.playlist.name} 的歌曲"
        tag.detailBtn.setOnClickListener { actions.loadPlaylistDetail(row.playlist.id) }
    }

    private fun createHistoryView(parent: ViewGroup): View {
        val host = LinearLayout(parent.context).apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val label = label(host, "", 14f, Color.WHITE, false)
        val repeatBtn = button(host, "再唱一次") {}
        host.addView(label, weightParams())
        host.addView(repeatBtn, wrapParams())
        host.tag = HistoryViewHolderTag(label, repeatBtn)
        return host
    }

    private fun bindHistory(view: View, row: HistoryPanelRow) {
        val tag = view.tag as? HistoryViewHolderTag ?: return
        val song = row.item.song
        tag.label.text = "${song?.title.orEmpty()} · ${song?.artist.orEmpty()} · ${row.item.playedByNick}"
        tag.repeatBtn.isEnabled = !row.repeatPending
        tag.repeatBtn.contentDescription = "再次点歌 ${song?.title.orEmpty()}"
        tag.repeatBtn.setOnClickListener { actions.repeatHistory(row.item.historyId) }
    }

    private fun createQueueView(parent: ViewGroup): View {
        val host = LinearLayout(parent.context).apply {
            layoutParams = recyclerMatchWrapParams()
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val label = label(host, "", 14f, dim(host), false)
        val topBtn = button(host, "顶歌") {}
        val deleteBtn = button(host, "删除") {}
        host.addView(label, weightParams())
        host.addView(topBtn, wrapParams())
        host.addView(deleteBtn, wrapParams())
        host.tag = QueueViewHolderTag(label, topBtn, deleteBtn)
        return host
    }

    private fun bindQueue(view: View, row: QueuePanelRow) {
        val tag = view.tag as? QueueViewHolderTag ?: return
        val song = row.entry.song
        tag.label.text = "%02d  %s · %s  %s".format(
            row.index + 1,
            song?.title.orEmpty(),
            song?.artist.orEmpty(),
            row.entry.orderedByNick ?: "",
        )
        val queueId = row.entry.queueId
        if (row.canManage && queueId != null) {
            tag.topBtn.visibility = View.VISIBLE
            tag.topBtn.isEnabled = !row.topPending
            tag.topBtn.contentDescription = "置顶 ${song?.title.orEmpty()}"
            tag.topBtn.setOnClickListener { actions.top(queueId) }

            tag.deleteBtn.visibility = View.VISIBLE
            tag.deleteBtn.isEnabled = !row.cancelPending
            tag.deleteBtn.contentDescription = "删除 ${song?.title.orEmpty()}"
            tag.deleteBtn.setOnClickListener {
                actions.confirmDangerous("删除已点歌曲", "将从队列删除《${song?.title.orEmpty()}》。") {
                    actions.cancel(queueId, song?.title.orEmpty())
                }
            }
        } else {
            tag.topBtn.visibility = View.GONE
            tag.deleteBtn.visibility = View.GONE
        }
    }

    private fun createMessageView(parent: ViewGroup): View =
        label(parent, "", 14f, dim(parent), false).apply {
            layoutParams = recyclerMatchWrapParams()
            setPadding(dp(parent, 8), dp(parent, 12), dp(parent, 8), dp(parent, 12))
        }

    private fun bindMessage(view: View, row: MessagePanelRow) {
        val tv = view as? TextView ?: return
        tv.text = row.text
    }

    private fun createActionView(parent: ViewGroup): View =
        button(parent, "") {}.apply {
            layoutParams = recyclerMatchWrapParams()
        }

    private fun bindAction(view: View, row: ActionPanelRow) {
        val btn = view as? Button ?: return
        btn.text = row.text
        btn.isEnabled = row.enabled
        btn.contentDescription = row.contentDescription
        btn.setOnClickListener { row.action() }
    }

    private fun button(parent: View, text: String, action: () -> Unit): Button =
        Button(parent.context).apply {
            id = View.generateViewId()
            this.text = text
            setOnClickListener { action() }
            minHeight = dp(parent, 48)
            minWidth = dp(parent, 48)
            isAllCaps = false
            isFocusable = true
            setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus) {
                    focused.post {
                        val rect = android.graphics.Rect()
                        focused.getDrawingRect(rect)
                        focused.requestRectangleOnScreen(rect, true)
                    }
                }
            }
        }

    private fun label(parent: View, text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(parent.context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dim(parent: View): Int = parent.context.getColor(R.color.dim)

    private fun dp(parent: View, value: Int): Int =
        (value * parent.resources.displayMetrics.density).toInt()

    private fun recyclerMatchWrapParams() = RecyclerView.LayoutParams(
        RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT,
    )

    private fun matchWrapParams() = LinearLayout.LayoutParams(-1, -2)

    private fun wrapParams() = LinearLayout.LayoutParams(-2, -2)

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f)
}
