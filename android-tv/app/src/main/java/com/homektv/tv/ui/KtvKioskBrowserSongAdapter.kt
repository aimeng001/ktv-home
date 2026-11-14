package com.homektv.tv.ui

import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.databinding.ItemKtvBrowserSongBinding
import com.homektv.tv.net.SongDto

/** Two-column song cards for the commercial kiosk browser. */
class KtvKioskBrowserSongAdapter(
    private val onOrder: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
    private val onOrderTop: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
    private val onToggleFavorite: (SongDto) -> Unit,
    private val imageLoader: ((url: String, callback: (Bitmap?) -> Unit) -> Unit)? = null,
) : ListAdapter<SongDto, KtvKioskBrowserSongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    private val submittingSongIds = mutableSetOf<Long>()
    private var queueProjection: Map<Long, SongQueueState> = emptyMap()
    private var favoriteIds: Set<Long> = emptySet()
    private var searchKeyword: String = ""

    init {
        try {
            setHasStableIds(true)
        } catch (_: Throwable) {
            // Android stub jar in unit tests has uninitialized Observable.
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    fun updateQueueProjection(projection: Map<Long, SongQueueState>) {
        queueProjection = projection
        notifyVisibleItems()
    }

    fun updateFavoriteIds(ids: Set<Long>) {
        favoriteIds = ids
        notifyVisibleItems()
    }

    fun setSearchKeyword(keyword: String) {
        val normalized = keyword.trim().uppercase()
        if (searchKeyword == normalized) return
        searchKeyword = normalized
        notifyVisibleItems()
    }

    private fun notifyVisibleItems() {
        if (itemCount == 0) return
        try {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        } catch (_: Throwable) {
            // Android's local JVM stubs may not have adapter observers initialized.
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder = SongViewHolder(
        binding = ItemKtvBrowserSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        ),
        onOrder = onOrder,
        onOrderTop = onOrderTop,
        onToggleFavorite = onToggleFavorite,
        imageLoader = imageLoader,
        submittingSongIds = submittingSongIds,
        getQueueProjection = { queueProjection },
        getFavoriteIds = { favoriteIds },
        getSearchKeyword = { searchKeyword },
    )

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(
        holder: SongViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_STATE_CHANGED)) {
            holder.bindDynamicState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class SongViewHolder(
        private val binding: ItemKtvBrowserSongBinding,
        private val onOrder: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
        private val onOrderTop: (SongDto, onComplete: (Boolean) -> Unit) -> Unit,
        private val onToggleFavorite: (SongDto) -> Unit,
        private val imageLoader: ((url: String, callback: (Bitmap?) -> Unit) -> Unit)? = null,
        private val submittingSongIds: MutableSet<Long>,
        private val getQueueProjection: () -> Map<Long, SongQueueState>,
        private val getFavoriteIds: () -> Set<Long>,
        private val getSearchKeyword: () -> String,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var hasVocalTrack = false
        private var boundSong: SongDto? = null
        private var boundQueueState: SongQueueState? = null

        init {
            val focusListener = View.OnFocusChangeListener { _, _ ->
                val hasFocus = binding.btnBrowserSongOrder.hasFocus() ||
                    binding.btnBrowserSongOrderTop.hasFocus() ||
                    binding.btnBrowserSongFavorite.hasFocus() ||
                    binding.root.hasFocus()
                binding.root.isSelected = hasFocus
                binding.panelBrowserSongActions.visibility = if (hasFocus) View.VISIBLE else View.GONE
                binding.txtBrowserSongVocal.visibility = if (hasFocus && hasVocalTrack) View.VISIBLE else View.GONE
                binding.btnBrowserSongOrder.isFocusable = hasFocus
                binding.btnBrowserSongOrderTop.isFocusable = hasFocus
                binding.btnBrowserSongFavorite.isFocusable = hasFocus
            }
            binding.btnBrowserSongOrder.onFocusChangeListener = focusListener
            binding.btnBrowserSongOrderTop.onFocusChangeListener = focusListener
            binding.btnBrowserSongFavorite.onFocusChangeListener = focusListener
            binding.root.onFocusChangeListener = focusListener

            normalizeButtonCompoundDrawables(binding.btnBrowserSongOrder, 14)
            normalizeButtonCompoundDrawables(binding.btnBrowserSongOrderTop, 14)
            normalizeButtonCompoundDrawables(binding.btnBrowserSongFavorite, 14)

            binding.btnBrowserSongFavorite.setOnClickListener {
                boundSong?.let(onToggleFavorite)
            }
            binding.btnBrowserSongOrder.setOnClickListener {
                boundSong?.let { song -> submitOrder(song, isTop = false, queueState = boundQueueState) }
            }
            binding.btnBrowserSongOrderTop.setOnClickListener {
                boundSong?.let { song -> submitOrder(song, isTop = true, queueState = boundQueueState) }
            }
            binding.root.setOnClickListener {
                if (binding.btnBrowserSongOrder.isEnabled) binding.btnBrowserSongOrder.performClick()
            }
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

        fun bind(song: SongDto, position: Int) {
            boundSong = song
            hasVocalTrack = song.hasVocalTrack
            val displayTitle = com.homektv.tv.ui.kiosk.SongDisplayFormatter.formatTitle(song.title)
            val displayArtist = com.homektv.tv.ui.kiosk.SongDisplayFormatter.formatArtist(song.artist)
            binding.root.contentDescription = "$displayTitle，$displayArtist"
            val initial = ArtistInitialResolver.resolve(displayArtist)
            binding.txtBrowserSongIndex.text = initial
            binding.txtBrowserSongIndex.visibility = View.VISIBLE
            binding.imgBrowserSongAvatar.setImageDrawable(null)
            binding.imgBrowserSongAvatar.visibility = View.GONE

            val avatarUrl = song.artistAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }
            binding.imgBrowserSongAvatar.tag = avatarUrl
            if (avatarUrl != null && imageLoader != null) {
                imageLoader.invoke(avatarUrl) { bitmap ->
                    if (bitmap != null && binding.imgBrowserSongAvatar.tag == avatarUrl) {
                        binding.imgBrowserSongAvatar.setImageBitmap(bitmap)
                        binding.imgBrowserSongAvatar.visibility = View.VISIBLE
                        binding.txtBrowserSongIndex.visibility = View.GONE
                    }
                }
            }
            binding.txtBrowserSongArtist.text =
                com.homektv.tv.ui.kiosk.SongDisplayFormatter.formatArtistSubtitle(song.artist, song.mediaType)
            val resolution = song.resolution?.trim()?.takeIf { it.isNotEmpty() }
            binding.txtBrowserSongQuality.text = when (song.mediaType.uppercase()) {
                "KTV_VIDEO" -> "KTV 视频 · ${resolution ?: "清晰度未标注"}"
                "MV" -> "MV 视频 · ${resolution ?: "清晰度未标注"}"
                else -> "音频"
            }
            binding.txtBrowserSongVocal.visibility = View.GONE

            bindDynamicState(song)
        }

        fun bindDynamicState(song: SongDto) {
            boundSong = song
            hasVocalTrack = song.hasVocalTrack
            renderTitle(song)

            val queueState = getQueueProjection()[song.id]
            boundQueueState = queueState
            val isSubmitting = submittingSongIds.contains(song.id)
            val orderState = SongOrderButtonPolicy.resolveStateOnBind(
                isTop = false,
                isSubmitting = isSubmitting,
                queueState = queueState,
                playable = song.playable,
            )
            binding.btnBrowserSongOrder.text = compactOrderMark(song.playable, isSubmitting, orderState.isEnabled)
            binding.btnBrowserSongOrder.isEnabled = orderState.isEnabled
            binding.btnBrowserSongOrder.tag = song.id
            binding.btnBrowserSongOrder.contentDescription = "${orderState.text}：${song.title}，${song.artist}"
            val topState = SongOrderButtonPolicy.resolveStateOnBind(
                isTop = true,
                isSubmitting = isSubmitting,
                queueState = queueState,
                playable = song.playable,
            )
            binding.btnBrowserSongOrderTop.text = compactOrderMark(song.playable, isSubmitting, topState.isEnabled)
            binding.btnBrowserSongOrderTop.isEnabled = topState.isEnabled
            binding.btnBrowserSongOrderTop.tag = song.id
            binding.btnBrowserSongOrderTop.contentDescription = "${topState.text}：${song.title}，${song.artist}"

            val favorite = getFavoriteIds().contains(song.id)
            binding.btnBrowserSongFavorite.text = ""
            binding.btnBrowserSongFavorite.isSelected = favorite
            binding.btnBrowserSongFavorite.contentDescription =
                if (favorite) "取消收藏：${song.title}，${song.artist}" else "收藏：${song.title}，${song.artist}"

            val hasFocus = binding.root.hasFocus() ||
                binding.btnBrowserSongOrder.hasFocus() ||
                binding.btnBrowserSongOrderTop.hasFocus() ||
                binding.btnBrowserSongFavorite.hasFocus()
            binding.root.isSelected = hasFocus
            binding.panelBrowserSongActions.visibility = if (hasFocus) View.VISIBLE else View.GONE
            binding.txtBrowserSongVocal.visibility = if (hasFocus && hasVocalTrack) View.VISIBLE else View.GONE
            binding.btnBrowserSongOrder.isFocusable = hasFocus
            binding.btnBrowserSongOrderTop.isFocusable = hasFocus
            binding.btnBrowserSongFavorite.isFocusable = hasFocus
        }

        private fun compactOrderMark(playable: Boolean, submitting: Boolean, enabled: Boolean): String = when {
            !playable -> "!"
            submitting -> "…"
            !enabled -> "✓"
            else -> ""
        }

        private fun renderTitle(song: SongDto) {
            val title = com.homektv.tv.ui.kiosk.SongDisplayFormatter.formatTitle(song.title)
            val keyword = getSearchKeyword()
            val matchIndex = if (keyword.isNotBlank()) title.indexOf(keyword, ignoreCase = true) else -1
            if (matchIndex >= 0) {
                val styled = SpannableString(title)
                styled.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(binding.root.context, R.color.ktv_focus_blue)),
                    matchIndex,
                    matchIndex + keyword.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                binding.txtBrowserSongTitle.text = styled
            } else {
                binding.txtBrowserSongTitle.text = title
            }

            binding.txtBrowserSongMatch.visibility = View.GONE
        }

        private fun highlightedMatch(label: String, value: String, keyword: String): SpannableString {
            val text = "$label$value"
            val styled = SpannableString(text)
            val matchIndex = value.indexOf(keyword, ignoreCase = true)
            if (matchIndex >= 0) {
                val start = label.length + matchIndex
                styled.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(binding.root.context, R.color.ktv_focus_blue)),
                    start,
                    start + keyword.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            return styled
        }

        private fun submitOrder(song: SongDto, isTop: Boolean, queueState: SongQueueState?) {
            if (!song.playable || submittingSongIds.contains(song.id)) return
            submittingSongIds.add(song.id)
            val targetButton = if (isTop) binding.btnBrowserSongOrderTop else binding.btnBrowserSongOrder
            val otherButton = if (isTop) binding.btnBrowserSongOrder else binding.btnBrowserSongOrderTop
            targetButton.text = SongOrderButtonPolicy.submittingText(isTop)
            targetButton.contentDescription = "正在处理：${song.title}，${song.artist}"
            targetButton.isEnabled = false
            otherButton.isEnabled = false
            val action = if (isTop) onOrderTop else onOrder
            action(song) { success ->
                submittingSongIds.remove(song.id)
                if (binding.btnBrowserSongOrder.tag == song.id || binding.btnBrowserSongOrderTop.tag == song.id) {
                    val latestQueueState = getQueueProjection()[song.id] ?: queueState
                    val orderState = SongOrderButtonPolicy.resolveStateOnBind(
                        isTop = false,
                        isSubmitting = false,
                        queueState = latestQueueState,
                        playable = song.playable,
                    )
                    val topState = SongOrderButtonPolicy.resolveStateOnBind(
                        isTop = true,
                        isSubmitting = false,
                        queueState = latestQueueState,
                        playable = song.playable,
                    )
                    binding.btnBrowserSongOrder.text = when {
                        !song.playable -> "!"
                        success && !isTop -> "✓"
                        !orderState.isEnabled -> "✓"
                        else -> ""
                    }
                    binding.btnBrowserSongOrder.isEnabled = if (success && !isTop) false else orderState.isEnabled
                    binding.btnBrowserSongOrder.contentDescription =
                        if (success && !isTop) "已点：${song.title}，${song.artist}"
                        else "${orderState.text}：${song.title}，${song.artist}"
                    binding.btnBrowserSongOrderTop.text = when {
                        !song.playable -> "!"
                        success && isTop -> "✓"
                        !topState.isEnabled -> "✓"
                        else -> ""
                    }
                    binding.btnBrowserSongOrderTop.isEnabled = if (success && isTop) false else topState.isEnabled
                    binding.btnBrowserSongOrderTop.contentDescription =
                        if (success && isTop) "已置顶：${song.title}，${song.artist}"
                        else "${topState.text}：${song.title}，${song.artist}"
                }
            }
        }
    }

    companion object {
        private const val PAYLOAD_STATE_CHANGED = "state_changed"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SongDto>() {
            override fun areItemsTheSame(oldItem: SongDto, newItem: SongDto): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: SongDto, newItem: SongDto): Boolean = oldItem == newItem
        }
    }
}
