package com.homektv.tv.ui

import android.app.Dialog
import android.content.Context
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.controller.ActionKey
import com.homektv.tv.controller.QueueDrawerActionPolicy
import com.homektv.tv.controller.QueuePermissionPolicy
import com.homektv.tv.databinding.DialogKtvQueueBinding
import com.homektv.tv.databinding.ItemHistoryDrawerRowBinding
import com.homektv.tv.databinding.ItemQueueDrawerRowBinding
import com.homektv.tv.net.RecentHistoryItem
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
    private var actionsAvailable: Boolean = true

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
            actionsAvailable = { actionsAvailable },
            onTop = onTop,
            onCancel = onCancel,
        )
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    fun updateActor(currentUserId: Long?, isHost: Boolean) {
        if (actorUserId == currentUserId && actorIsHost == isHost) return
        val changedPositions = currentList.mapIndexedNotNull { index, entry ->
            val wasManageable = QueuePermissionPolicy.canManage(entry, actorUserId, actorIsHost)
            val willBeManageable = QueuePermissionPolicy.canManage(entry, currentUserId, isHost)
            index.takeIf { wasManageable != willBeManageable }
        }
        actorUserId = currentUserId
        actorIsHost = isHost
        changedPositions.forEach(::notifyItemChanged)
    }

    fun updatePendingActions(actions: Set<ActionKey>) {
        if (pendingActions == actions) return
        val changedQueueIds = (pendingActions union actions)
            .asSequence()
            .filter { it.kind == "control:top" || it.kind == "control:cancel" }
            .filter { (it in pendingActions) != (it in actions) }
            .map { it.resourceId }
            .toSet()
        pendingActions = actions
        currentList.forEachIndexed { index, entry ->
            if (entry.queueId in changedQueueIds) notifyItemChanged(index)
        }
    }

    fun setActionsAvailable(available: Boolean) {
        if (actionsAvailable == available) return
        actionsAvailable = available
        notifyDataSetChanged()
    }

    class QueueViewHolder(
        private val binding: ItemQueueDrawerRowBinding,
        private val currentUserId: () -> Long?,
        private val isHost: () -> Boolean,
        private val pendingActions: () -> Set<ActionKey>,
        private val actionsAvailable: () -> Boolean,
        private val onTop: (Long) -> Unit,
        private val onCancel: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            normalizeButtonCompoundDrawables(binding.btnQueueTop, 16)
            normalizeButtonCompoundDrawables(binding.btnQueueDelete, 16)
        }

        private fun normalizeButtonCompoundDrawables(button: android.widget.Button, sizeDp: Int) {
            val density = button.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt()
            val drawables = button.compoundDrawablesRelative
            var changed = false
            drawables.forEach { d ->
                if (d != null) {
                    d.setBounds(0, 0, sizePx, sizePx)
                    changed = true
                }
            }
            if (changed) {
                button.setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3])
            }
        }

        fun bind(entry: QueueEntry, index: Int) {
            val qId = entry.queueId
            val isPlaying = entry.status.equals("playing", ignoreCase = true) || index == 1
            if (isPlaying) {
                binding.txtQueueIndex.visibility = View.GONE
                binding.spectrumQueueRow.visibility = View.VISIBLE
            } else {
                binding.txtQueueIndex.visibility = View.VISIBLE
                binding.spectrumQueueRow.visibility = View.GONE
                binding.txtQueueIndex.text = binding.root.context.getString(R.string.queue_row_index, index)
            }
            binding.txtQueueTitle.text = entry.song?.title ?: binding.root.context.getString(R.string.unknown_song)
        val nick = entry.orderedByNick?.takeIf { it.isNotBlank() }
            ?: binding.root.context.getString(R.string.anonymous_user)
        binding.txtQueueSubtitle.text = binding.root.context.getString(
            R.string.queue_row_subtitle,
            entry.song?.artist.orEmpty(),
            nick,
        )

            val canManage = qId != null && QueuePermissionPolicy.canManage(entry, currentUserId(), isHost())
            val topPending = qId != null && ActionKey("control:top", qId) in pendingActions()
            val cancelPending = qId != null && ActionKey("control:cancel", qId) in pendingActions()
            binding.btnQueueTop.visibility = if (canManage) View.VISIBLE else View.GONE
            binding.btnQueueDelete.visibility = if (canManage) View.VISIBLE else View.GONE
            binding.btnQueueTop.isEnabled = canManage && actionsAvailable() && !topPending
            binding.btnQueueDelete.isEnabled = canManage && actionsAvailable() && !cancelPending

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

class KtvHistoryDrawerAdapter(
    private val onRepeatHistory: (Long) -> Unit,
) : ListAdapter<RecentHistoryItem, KtvHistoryDrawerAdapter.HistoryViewHolder>(DIFF_CALLBACK) {
    private var pendingHistoryIds: Set<Long> = emptySet()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val historyId = getItem(position).historyId
        return if (historyId > 0) historyId else Long.MIN_VALUE + position
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryDrawerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return HistoryViewHolder(binding, { pendingHistoryIds }, onRepeatHistory)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    fun updatePendingActions(actions: Set<ActionKey>) {
        val next = actions.asSequence()
            .filter { it.kind == "history_repeat" }
            .map { it.resourceId }
            .toSet()
        if (next == pendingHistoryIds) return
        val changedIds = pendingHistoryIds union next
        pendingHistoryIds = next
        currentList.forEachIndexed { index, item ->
            if (item.historyId in changedIds) notifyItemChanged(index)
        }
    }

    class HistoryViewHolder(
        private val binding: ItemHistoryDrawerRowBinding,
        private val pendingIds: () -> Set<Long>,
        private val onRepeatHistory: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentHistoryItem, index: Int) {
            val song = item.song
            val title = song?.title?.takeIf(String::isNotBlank)
                ?: binding.root.context.getString(R.string.unknown_song)
            val subtitle = buildList {
                song?.artist?.takeIf(String::isNotBlank)?.let(::add)
                item.playedByNick.takeIf(String::isNotBlank)?.let(::add)
                item.playedAt.takeIf(String::isNotBlank)?.let(::add)
            }.joinToString(" · ")
            binding.txtHistoryIndex.text = index.toString()
            binding.txtHistoryTitle.text = title
            binding.txtHistorySubtitle.text = subtitle

            val canRepeat = item.historyId > 0 && item.historyId !in pendingIds()
            binding.root.isEnabled = canRepeat
            binding.root.contentDescription = binding.root.context.getString(
                R.string.queue_drawer_history_repeat_description,
                title,
            )
            binding.root.setOnClickListener {
                if (canRepeat) onRepeatHistory(item.historyId)
            }
        }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecentHistoryItem>() {
            override fun areItemsTheSame(oldItem: RecentHistoryItem, newItem: RecentHistoryItem): Boolean =
                if (oldItem.historyId > 0 && newItem.historyId > 0) oldItem.historyId == newItem.historyId
                else oldItem == newItem

            override fun areContentsTheSame(oldItem: RecentHistoryItem, newItem: RecentHistoryItem): Boolean =
                oldItem == newItem
        }
    }
}

enum class KtvQueueDrawerTab { DOWNLOADS, QUEUE, HISTORY }

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
    private val initialTab: KtvQueueDrawerTab = KtvQueueDrawerTab.QUEUE,
    private val initiatingFocusView: View? = null,
) : Dialog(context) {

    private data class BackgroundFocusability(
        val view: View,
        val focusable: Boolean,
        val focusableInTouchMode: Boolean,
    )

    private lateinit var binding: DialogKtvQueueBinding
    private lateinit var adapter: KtvQueueDrawerAdapter
    private lateinit var historyAdapter: KtvHistoryDrawerAdapter
    private var actionsAvailable = false
    private var hasWaitingItems = false
    private var selectedTab: KtvQueueDrawerTab? = null
    private var isPlaying = false
    private var returnFocus: View? = null
    private var suspendedBackgroundFocusability: List<BackgroundFocusability> = emptyList()
    private val equalizerAnimators = mutableListOf<ObjectAnimator>()

    var onDismissDrawer: (() -> Unit)? = null
    var onRetryQueue: (() -> Unit)? = null
    var onHistoryTabSelected: (() -> Unit)? = null
    var onRepeatHistory: ((Long) -> Unit)? = null
    var onRetryHistory: (() -> Unit)? = null

    override fun show() {
        returnFocus = initiatingFocusView?.takeIf { it.hasFocus() }
        returnFocus?.clearFocus()
        super.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(160f / 255f)
        }

        adapter = KtvQueueDrawerAdapter(currentUserId, isHost, onTop, onCancel)
        binding.drawerRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.drawerRecyclerView.adapter = adapter
        historyAdapter = KtvHistoryDrawerAdapter { id -> onRepeatHistory?.invoke(id) }
        binding.rvHistoryDrawer.layoutManager = LinearLayoutManager(context)
        binding.rvHistoryDrawer.adapter = historyAdapter
        addEqualizerBars()

        binding.btnDrawerClose.setOnClickListener { dismiss() }
        binding.drawerTabDownloads.setOnClickListener { selectTab(KtvQueueDrawerTab.DOWNLOADS) }
        binding.drawerTabQueue.setOnClickListener { selectTab(KtvQueueDrawerTab.QUEUE) }
        binding.drawerTabHistory.setOnClickListener { selectTab(KtvQueueDrawerTab.HISTORY) }
        binding.btnDrawerShuffle.setOnClickListener {
            binding.btnDrawerShuffle.isEnabled = false
            onShuffle()
        }
        binding.btnDrawerRetry.setOnClickListener {
            binding.btnDrawerRetry.isEnabled = false
            binding.btnDrawerClose.requestFocus()
            onRetryQueue?.invoke()
        }
        binding.btnHistoryRetry.setOnClickListener {
            binding.btnHistoryRetry.isEnabled = false
            onRetryHistory?.invoke()
        }
        binding.queueDrawerRoot.setOnClickListener { dismiss() }
        binding.queueDrawerPanel.setOnClickListener { /* 阻止冒泡 */ }

        setOnShowListener {
            coordinator?.setModalActive(true)
            binding.btnDrawerClose.post {
                initiatingFocusView?.rootView?.let(::suspendBackgroundFocus)
                binding.btnDrawerClose.requestRemoteFocus()
            }
        }
        setOnDismissListener {
            stopEqualizer()
            coordinator?.setModalActive(false)
            onDismissDrawer?.invoke()
            restoreBackgroundFocus()
            returnFocus?.let { view ->
                view.post {
                    if (view.isAttachedToWindow && view.isShown && view.isFocusable) {
                        view.requestRemoteFocus()
                    }
                }
            }
            returnFocus = null
        }
        selectTab(initialTab, requestHistory = false)
    }

    private fun suspendBackgroundFocus(root: View) {
        if (suspendedBackgroundFocusability.isNotEmpty()) return
        val snapshot = mutableListOf<BackgroundFocusability>()
        fun visit(view: View) {
            val wasFocusable = view.isFocusable
            val wasFocusableInTouchMode = view.isFocusableInTouchMode
            if (wasFocusable || wasFocusableInTouchMode) {
                snapshot += BackgroundFocusability(view, wasFocusable, wasFocusableInTouchMode)
                view.clearFocus()
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.refreshDrawableState()
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        suspendedBackgroundFocusability = snapshot
    }

    private fun restoreBackgroundFocus() {
        suspendedBackgroundFocusability.forEach { state ->
            state.view.isFocusableInTouchMode = state.focusableInTouchMode
            state.view.isFocusable = state.focusable
            state.view.refreshDrawableState()
            state.view.invalidate()
        }
        suspendedBackgroundFocusability = emptyList()
    }

    fun submitQueue(nowPlayingText: String, waitingList: List<QueueEntry>, isPlaying: Boolean = false) {
        binding.txtDrawerTitle.text = context.getString(R.string.queue_drawer_title_count, waitingList.size)
        binding.txtDrawerNowPlaying.text = nowPlayingText
        this.isPlaying = isPlaying
        updateEqualizer()
        hasWaitingItems = waitingList.isNotEmpty()
        adapter.submitList(waitingList)

        val isEmpty = waitingList.isEmpty()
        binding.txtDrawerEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.drawerRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        setQueueStatus(message = null, retryAvailable = false)
        binding.btnDrawerClose.nextFocusDownId = if (isEmpty) binding.btnDrawerClose.id else binding.drawerRecyclerView.id
        updateShuffleEnabled()
    }

    fun showUnavailable(message: String, retryAvailable: Boolean) {
        setActionsAvailable(false)
        binding.txtDrawerTitle.text = "已点歌曲"
        binding.txtDrawerNowPlaying.text = "播放与队列状态尚未同步"
        isPlaying = false
        updateEqualizer()
        binding.txtDrawerEmpty.visibility = View.GONE
        binding.drawerRecyclerView.visibility = View.GONE
        hasWaitingItems = false
        setQueueStatus(message, retryAvailable)
        updateShuffleEnabled()
    }

    fun setQueueStatus(message: String?, retryAvailable: Boolean) {
        binding.txtDrawerQueueStatus.text = message.orEmpty()
        binding.txtDrawerQueueStatus.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.btnDrawerRetry.visibility = if (retryAvailable) View.VISIBLE else View.GONE
        binding.btnDrawerRetry.isEnabled = retryAvailable
        if (retryAvailable) {
            binding.btnDrawerShuffle.nextFocusRightId = binding.btnDrawerRetry.id
            binding.btnDrawerRetry.nextFocusLeftId = binding.btnDrawerShuffle.id
            binding.btnDrawerRetry.nextFocusRightId = binding.btnDrawerClose.id
            binding.btnDrawerClose.nextFocusLeftId = binding.btnDrawerRetry.id
        } else {
            binding.btnDrawerShuffle.nextFocusRightId = binding.btnDrawerClose.id
            binding.btnDrawerClose.nextFocusLeftId = binding.btnDrawerShuffle.id
        }
        binding.btnDrawerClose.nextFocusDownId = when {
            retryAvailable -> binding.btnDrawerRetry.id
            hasWaitingItems -> binding.drawerRecyclerView.id
            else -> binding.btnDrawerClose.id
        }
    }

    fun setActionsAvailable(available: Boolean) {
        actionsAvailable = available
        if (::adapter.isInitialized) adapter.setActionsAvailable(available)
        updateShuffleEnabled()
    }

    private fun updateShuffleEnabled() {
        if (!::binding.isInitialized) return
        binding.btnDrawerShuffle.isEnabled = actionsAvailable && hasWaitingItems
    }

    fun updateActor(currentUserId: Long?, isHost: Boolean) {
        if (::adapter.isInitialized) adapter.updateActor(currentUserId, isHost)
    }

    fun updatePendingActions(actions: Set<ActionKey>) {
        if (::adapter.isInitialized) adapter.updatePendingActions(actions)
        if (::historyAdapter.isInitialized) historyAdapter.updatePendingActions(actions)
        if (::binding.isInitialized) {
            val noShufflePending = QueueDrawerActionPolicy.isEnabled(
                action = "control:shuffle",
                pendingActions = actions,
            )
            binding.btnDrawerShuffle.isEnabled = actionsAvailable && hasWaitingItems && noShufflePending
        }
    }

    fun selectInitialTab() {
        selectTab(initialTab, requestHistory = initialTab == KtvQueueDrawerTab.HISTORY)
        binding.btnDrawerClose.nextFocusDownId = when (initialTab) {
            KtvQueueDrawerTab.DOWNLOADS -> binding.drawerTabDownloads.id
            KtvQueueDrawerTab.QUEUE -> binding.drawerTabQueue.id
            KtvQueueDrawerTab.HISTORY -> binding.drawerTabHistory.id
        }
    }

    fun renderHistoryState(
        history: List<RecentHistoryItem>,
        loading: Boolean,
        errorMessage: String?,
        retryAvailable: Boolean,
    ) {
        historyAdapter.submitList(history)
        val hasRows = history.isNotEmpty()
        binding.rvHistoryDrawer.visibility = if (hasRows) View.VISIBLE else View.GONE
        binding.txtHistoryEmpty.visibility = if (!hasRows && !loading && errorMessage == null) View.VISIBLE else View.GONE
        binding.txtHistoryEmpty.text = context.getString(R.string.queue_drawer_history_empty)
        val status = when {
            loading -> context.getString(R.string.queue_drawer_history_loading)
            errorMessage != null -> errorMessage
            else -> null
        }
        binding.txtHistoryStatus.text = status.orEmpty()
        binding.txtHistoryStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        binding.btnHistoryRetry.visibility = if (retryAvailable) View.VISIBLE else View.GONE
        binding.btnHistoryRetry.isEnabled = retryAvailable
        binding.drawerTabHistory.nextFocusDownId = when {
            retryAvailable -> binding.btnHistoryRetry.id
            hasRows -> binding.rvHistoryDrawer.id
            else -> binding.drawerTabHistory.id
        }
    }

    private fun selectTab(tab: KtvQueueDrawerTab, requestHistory: Boolean = true) {
        selectedTab = tab
        binding.drawerQueuePage.visibility = if (tab == KtvQueueDrawerTab.QUEUE) View.VISIBLE else View.GONE
        binding.drawerHistoryPage.visibility = if (tab == KtvQueueDrawerTab.HISTORY) View.VISIBLE else View.GONE
        binding.drawerDownloadsPage.visibility = if (tab == KtvQueueDrawerTab.DOWNLOADS) View.VISIBLE else View.GONE
        setTabStyle(binding.drawerTabDownloads, binding.txtDrawerTabDownloads, binding.lineDrawerTabDownloads, tab == KtvQueueDrawerTab.DOWNLOADS)
        setTabStyle(binding.drawerTabQueue, binding.txtDrawerTabQueue, binding.lineDrawerTabQueue, tab == KtvQueueDrawerTab.QUEUE)
        setTabStyle(binding.drawerTabHistory, binding.txtDrawerTabHistory, binding.lineDrawerTabHistory, tab == KtvQueueDrawerTab.HISTORY)
        if (tab == KtvQueueDrawerTab.QUEUE) updateEqualizer() else stopEqualizer()
        if (tab == KtvQueueDrawerTab.HISTORY && requestHistory) {
            renderHistoryState(emptyList(), loading = true, errorMessage = null, retryAvailable = false)
            onHistoryTabSelected?.invoke()
        }
    }

    private fun setTabStyle(container: View, label: TextView, underline: View, selected: Boolean) {
        container.isSelected = selected
        label.setTextColor(Color.parseColor(if (selected) "#E93359" else "#FFFFFFFF"))
        underline.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun addEqualizerBars() {
        val density = binding.root.resources.displayMetrics.density
        val heights = listOf(8, 16, 22, 14, 9)
        heights.forEachIndexed { index, heightDp ->
            val bar = View(context).apply {
                setBackgroundColor(context.getColor(R.color.ktv_queue_playing))
                pivotY = heightDp * density
            }
            binding.playingEqualizer.addView(
                bar,
                LinearLayout.LayoutParams((3 * density).toInt(), (heightDp * density).toInt()).apply {
                    marginStart = (1 * density).toInt()
                    marginEnd = (1 * density).toInt()
                    gravity = Gravity.BOTTOM
                },
            )
            equalizerAnimators += ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.35f, 0.9f, 0.45f).apply {
                duration = 420L + index * 55L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
        }
    }

    private fun updateEqualizer() {
        if (selectedTab != KtvQueueDrawerTab.QUEUE || !isPlaying) {
            stopEqualizer()
            return
        }
        equalizerAnimators.forEach { animator -> if (!animator.isStarted) animator.start() }
    }

    private fun stopEqualizer() {
        equalizerAnimators.forEach { animator ->
            animator.cancel()
            animator.animatedValue?.let { value ->
                // The animation is a playback-state indicator, not measured audio data.
                (animator.target as? View)?.scaleY = if (value is Float) value.coerceAtMost(0.65f) else 0.5f
            }
        }
    }
}
