package com.homektv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.databinding.ItemKioskSongRowBinding
import com.homektv.tv.net.SongDto

/**
 * 点歌台曲目列表适配器，支持大屏按键交互与原伴唱标签显示。
 */
class KtvKioskSongAdapter(
    private val onOrder: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
    private val onOrderTop: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
) : ListAdapter<SongDto, KtvKioskSongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    init {
        try {
            setHasStableIds(true)
        } catch (_: Throwable) {
            // Android stub jar in unit tests has uninitialized Observable
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    private val submittingSongIds = mutableSetOf<Long>()
    private var queueProjection: Map<Long, SongQueueState> = emptyMap()

    fun updateQueueProjection(projection: Map<Long, SongQueueState>) {
        queueProjection = projection
        val count = itemCount
        if (QueueProjectionPolicy.shouldNotifyChange(count)) {
            try {
                notifyItemRangeChanged(0, count)
            } catch (_: Throwable) {
                // Unit tests without mocked adapter observers
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemKioskSongRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SongViewHolder(
            binding = binding,
            onOrder = onOrder,
            onOrderTop = onOrderTop,
            submittingSongIds = submittingSongIds,
            getQueueProjection = { queueProjection },
        )
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SongViewHolder(
        private val binding: ItemKioskSongRowBinding,
        private val onOrder: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
        private val onOrderTop: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
        private val submittingSongIds: MutableSet<Long>,
        private val getQueueProjection: () -> Map<Long, SongQueueState>,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: SongDto) {
            binding.txtSongTitle.text = song.title
            binding.txtSongArtist.text = song.artist

            val showBadge = song.hasVocalTrack || song.mediaType != "AUDIO"
            binding.txtSongBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
            binding.txtSongBadge.text = when {
                song.hasVocalTrack -> "伴唱"
                song.mediaType == "KTV_VIDEO" -> "KTV"
                song.mediaType == "MV" -> "MV"
                else -> ""
            }

            binding.btnOrder.tag = song.id
            binding.btnOrderTop.tag = song.id
            val isSubmitting = submittingSongIds.contains(song.id)
            val queueState = getQueueProjection()[song.id]

            val orderState = SongOrderButtonPolicy.resolveStateOnBind(
                isTop = false,
                isSubmitting = isSubmitting,
                queueState = queueState,
                playable = song.playable,
            )
            binding.btnOrder.text = orderState.text
            binding.btnOrder.isEnabled = orderState.isEnabled

            val topState = SongOrderButtonPolicy.resolveStateOnBind(
                isTop = true,
                isSubmitting = isSubmitting,
                queueState = queueState,
                playable = song.playable,
            )
            binding.btnOrderTop.text = topState.text
            binding.btnOrderTop.isEnabled = topState.isEnabled

            binding.btnOrder.setOnClickListener {
                submittingSongIds.add(song.id)
                binding.btnOrder.isEnabled = false
                binding.btnOrderTop.isEnabled = false
                val submitting = SongOrderButtonPolicy.onSubmit(isTop = false)
                binding.btnOrder.text = submitting.text
                onOrder(song) { success ->
                    submittingSongIds.remove(song.id)
                    if (SongOrderButtonPolicy.shouldApplyResult(binding.btnOrder.tag as? Long, song.id)) {
                        val currentQueueState = getQueueProjection()[song.id]
                        val resolvedOrder = SongOrderButtonPolicy.onComplete(
                            isTop = false, success = success, playable = song.playable)
                        binding.btnOrder.text = if (success) resolvedOrder.text else (currentQueueState?.let {
                            SongOrderButtonPolicy.resolveStateOnBind(isTop = false, isSubmitting = false, queueState = it).text
                        } ?: resolvedOrder.text)
                        binding.btnOrder.isEnabled = if (success) false else (song.playable && currentQueueState == null)

                        val resolvedTop = SongOrderButtonPolicy.resolveStateOnBind(
                            isTop = true,
                            isSubmitting = false,
                            queueState = currentQueueState,
                            playable = song.playable,
                        )
                        binding.btnOrderTop.text = resolvedTop.text
                        binding.btnOrderTop.isEnabled = resolvedTop.isEnabled
                    }
                }
            }

            binding.btnOrderTop.setOnClickListener {
                submittingSongIds.add(song.id)
                binding.btnOrder.isEnabled = false
                binding.btnOrderTop.isEnabled = false
                val submitting = SongOrderButtonPolicy.onSubmit(isTop = true)
                binding.btnOrderTop.text = submitting.text
                onOrderTop(song) { success ->
                    submittingSongIds.remove(song.id)
                    if (SongOrderButtonPolicy.shouldApplyResult(binding.btnOrderTop.tag as? Long, song.id)) {
                        val currentQueueState = getQueueProjection()[song.id]
                        val resolvedTop = SongOrderButtonPolicy.onComplete(
                            isTop = true, success = success, playable = song.playable)
                        binding.btnOrderTop.text = resolvedTop.text
                        binding.btnOrderTop.isEnabled = resolvedTop.isEnabled

                        val resolvedOrder = SongOrderButtonPolicy.resolveStateOnBind(
                            isTop = false,
                            isSubmitting = false,
                            queueState = currentQueueState,
                            playable = song.playable,
                        )
                        binding.btnOrder.text = resolvedOrder.text
                        binding.btnOrder.isEnabled = resolvedOrder.isEnabled
                    }
                }
            }

            binding.root.setOnClickListener {
                if (binding.btnOrder.isEnabled) {
                    binding.btnOrder.performClick()
                }
            }
        }
    }

    object SongOrderButtonPolicy {
        data class ButtonState(val text: String, val isEnabled: Boolean)

        fun initialText(isTop: Boolean): String = if (isTop) "优先" else "点歌"
        fun submittingText(isTop: Boolean): String = if (isTop) "插播中..." else "点歌中..."
        fun successText(isTop: Boolean): String = if (isTop) "已置顶" else "已点"

        fun shouldApplyResult(boundSongId: Long?, targetSongId: Long): Boolean =
            boundSongId != null && boundSongId == targetSongId

        fun resolveStateOnBind(
            isTop: Boolean,
            isSubmitting: Boolean,
            queueState: SongQueueState? = null,
            playable: Boolean = true,
        ): ButtonState = when {
            !playable -> ButtonState(text = "准备中", isEnabled = false)
            isSubmitting -> ButtonState(text = submittingText(isTop), isEnabled = false)
            queueState == SongQueueState.Playing -> if (isTop) {
                ButtonState(text = initialText(isTop), isEnabled = false)
            } else {
                ButtonState(text = "演唱中", isEnabled = false)
            }
            queueState is SongQueueState.Waiting -> if (isTop) {
                ButtonState(text = initialText(isTop), isEnabled = queueState.canManage)
            } else {
                ButtonState(text = successText(isTop), isEnabled = false)
            }
            else -> ButtonState(text = initialText(isTop), isEnabled = true)
        }

        fun onSubmit(isTop: Boolean): ButtonState =
            ButtonState(text = submittingText(isTop), isEnabled = false)

        fun onComplete(isTop: Boolean, success: Boolean, playable: Boolean = true): ButtonState =
            if (!playable) {
                ButtonState(text = "准备中", isEnabled = false)
            } else if (success) {
                ButtonState(text = successText(isTop), isEnabled = false)
            } else {
                ButtonState(text = initialText(isTop), isEnabled = true)
            }
    }

    object QueueProjectionPolicy {
        fun shouldNotifyChange(itemCount: Int): Boolean = itemCount > 0
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SongDto>() {
            override fun areItemsTheSame(oldItem: SongDto, newItem: SongDto): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SongDto, newItem: SongDto): Boolean =
                oldItem == newItem
        }
    }
}
