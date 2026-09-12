package com.homektv.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.controller.ActionKey
import com.homektv.tv.controller.QueueDrawerActionPolicy
import com.homektv.tv.controller.QueuePermissionPolicy
import com.homektv.tv.databinding.DialogKtvQueueBinding
import com.homektv.tv.databinding.ItemQueueDrawerRowBinding
import com.homektv.tv.net.QueueEntry

/**
 * 队列抽屉列表适配器，根据本人与房主权限动态控制置顶与删除按键。
 */
class KtvQueueDrawerAdapter(
    currentUserId: Long?,
    isHost: Boolean,
    private val onTop: (Long) -> Unit,
    private val onCancel: (Long) -> Unit,
) : ListAdapter<QueueEntry, KtvQueueDrawerAdapter.QueueViewHolder>(DIFF_CALLBACK) {

    private var actorUserId: Long? = currentUserId
    private var actorIsHost: Boolean = isHost
    private var pendingActions: Set<ActionKey> = emptySet()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).queueId ?: (-position.toLong() - 1L)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueDrawerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return QueueViewHolder(
            binding = binding,
            currentUserId = { actorUserId },
            isHost = { actorIsHost },
            pendingActions = { pendingActions },
            onTop = onTop,
            onCancel = onCancel,
        )
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    fun updateActor(currentUserId: Long?, isHost: Boolean) {
        if (actorUserId == currentUserId && actorIsHost == isHost) return
        actorUserId = currentUserId
        actorIsHost = isHost
        notifyDataSetChanged()
    }

    fun updatePendingActions(actions: Set<ActionKey>) {
        if (pendingActions == actions) return
        pendingActions = actions
        notifyDataSetChanged()
    }

    class QueueViewHolder(
        private val binding: ItemQueueDrawerRowBinding,
        private val currentUserId: () -> Long?,
        private val isHost: () -> Boolean,
        private val pendingActions: () -> Set<ActionKey>,
        private val onTop: (Long) -> Unit,
        private val onCancel: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: QueueEntry, index: Int) {
            val qId = entry.queueId
            binding.txtQueueIndex.text = index.toString()
            binding.txtQueueTitle.text = entry.song?.title ?: "未知歌曲"
            val nick = entry.orderedByNick?.ifEmpty { "匿名" } ?: "匿名"
            binding.txtQueueSubtitle.text = "${entry.song?.artist ?: ""} · 点歌人: $nick"

            val canManage = qId != null && QueuePermissionPolicy.canManage(entry, currentUserId(), isHost())
            val topPending = qId != null && ActionKey("control:top", qId) in pendingActions()
            val cancelPending = qId != null && ActionKey("control:cancel", qId) in pendingActions()
            binding.btnQueueTop.visibility = if (canManage) View.VISIBLE else View.GONE
            binding.btnQueueDelete.visibility = if (canManage) View.VISIBLE else View.GONE
            binding.btnQueueTop.isEnabled = canManage && !topPending
            binding.btnQueueDelete.isEnabled = canManage && !cancelPending

            if (qId != null) {
                binding.btnQueueTop.setOnClickListener {
                    binding.btnQueueTop.isEnabled = false
                    onTop(qId)
                }
                binding.btnQueueDelete.setOnClickListener {
                    binding.btnQueueDelete.isEnabled = false
                    onCancel(qId)
                }
            } else {
                binding.btnQueueTop.setOnClickListener(null)
                binding.btnQueueDelete.setOnClickListener(null)
            }
        }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<QueueEntry>() {
            override fun areItemsTheSame(oldItem: QueueEntry, newItem: QueueEntry): Boolean =
                oldItem.queueId == newItem.queueId

            override fun areContentsTheSame(oldItem: QueueEntry, newItem: QueueEntry): Boolean =
                oldItem == newItem
        }
    }
}

/**
 * 类似商用点歌台的右侧滑出已点歌曲管理抽屉。
 */
class KtvQueueDrawerDialog(
    context: Context,
    private val currentUserId: Long?,
    private val isHost: Boolean,
    private val coordinator: KioskModeCoordinator?,
    private val onTop: (Long) -> Unit,
    private val onCancel: (Long) -> Unit,
    private val onShuffle: () -> Unit,
) : Dialog(context) {

    private lateinit var binding: DialogKtvQueueBinding
    private lateinit var adapter: KtvQueueDrawerAdapter

    var onDismissDrawer: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }

        adapter = KtvQueueDrawerAdapter(currentUserId, isHost, onTop, onCancel)
        binding.drawerRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.drawerRecyclerView.adapter = adapter

        binding.btnDrawerClose.setOnClickListener { dismiss() }
        binding.btnDrawerShuffle.setOnClickListener {
            binding.btnDrawerShuffle.isEnabled = false
            onShuffle()
        }
        binding.queueDrawerRoot.setOnClickListener { dismiss() }
        binding.queueDrawerPanel.setOnClickListener { /* 阻止冒泡 */ }

        setOnShowListener { coordinator?.setModalActive(true) }
        setOnDismissListener {
            coordinator?.setModalActive(false)
            onDismissDrawer?.invoke()
        }
    }

    fun submitQueue(nowPlayingText: String, waitingList: List<QueueEntry>) {
        binding.txtDrawerTitle.text = "已点歌曲 (${waitingList.size})"
        binding.txtDrawerNowPlaying.text = nowPlayingText
        adapter.submitList(waitingList)

        val isEmpty = waitingList.isEmpty()
        binding.txtDrawerEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.drawerRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    fun updateActor(currentUserId: Long?, isHost: Boolean) {
        if (::adapter.isInitialized) adapter.updateActor(currentUserId, isHost)
    }

    fun updatePendingActions(actions: Set<ActionKey>) {
        if (::adapter.isInitialized) adapter.updatePendingActions(actions)
        if (::binding.isInitialized) {
            binding.btnDrawerShuffle.isEnabled = QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = actions,
            )
        }
    }
}
