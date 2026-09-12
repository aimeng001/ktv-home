package com.homektv.tv.ui

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.controller.ActionKey
import com.homektv.tv.net.PlaylistSummary

/** TV-friendly playlist rows with separate detail and order-all actions. */
class KtvKioskPlaylistAdapter(
    private val onOpen: (PlaylistSummary) -> Unit,
    private val onOrder: (PlaylistSummary) -> Unit,
) : ListAdapter<PlaylistSummary, KtvKioskPlaylistAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var pendingOrderIds: Set<Long> = emptySet()

    fun updatePendingActions(actions: Set<ActionKey>) {
        val nextPendingOrderIds = actions.asSequence()
            .filter { it.kind == "playlist_order" }
            .map { it.resourceId }
            .toSet()
        if (nextPendingOrderIds == pendingOrderIds) return
        pendingOrderIds = nextPendingOrderIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(8, 4, 8, 4)
        }
        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val open = Button(parent.context).apply {
            text = "查看"
            isAllCaps = false
            minHeight = 48
            isFocusable = true
        }
        val order = Button(parent.context).apply {
            isAllCaps = false
            minHeight = 48
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = 8 }
        }
        root.addView(title)
        root.addView(open)
        root.addView(order)
        return ViewHolder(root, title, open, order)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = getItem(position)
        holder.title.text = "${playlist.name.ifBlank { "未命名歌单" }} · ${playlist.songCount} 首"
        holder.title.contentDescription = playlist.description.ifBlank { playlist.name }
        holder.open.setOnClickListener { onOpen(playlist) }
        val orderState = KtvKioskPlaylistOrderPolicy.resolve(playlist.id in pendingOrderIds)
        holder.order.text = orderState.text
        holder.order.isEnabled = orderState.isEnabled
        holder.order.setOnClickListener { onOrder(playlist) }
    }

    class ViewHolder(
        root: LinearLayout,
        val title: TextView,
        val open: Button,
        val order: Button,
    ) : RecyclerView.ViewHolder(root)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PlaylistSummary>() {
            override fun areItemsTheSame(oldItem: PlaylistSummary, newItem: PlaylistSummary): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PlaylistSummary, newItem: PlaylistSummary): Boolean =
                oldItem == newItem
        }
    }
}
