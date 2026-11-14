package com.homektv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.databinding.ItemSingerCardBinding
import com.homektv.tv.net.ArtistItem

/**
 * 歌星网格适配器，支持大屏遥控器焦点放大、首字占位与有界头像加载。
 *
 * Adapter for the Singer/Artist grid with focus scaling and fallback avatars.
 */
class SingerCardAdapter(
    private val onArtistClick: (ArtistItem) -> Unit,
    private val imageLoader: ((url: String, callback: (android.graphics.Bitmap?) -> Unit) -> Unit)? = null,
) : ListAdapter<ArtistItem, SingerCardAdapter.SingerViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SingerViewHolder {
        val binding = ItemSingerCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SingerViewHolder(binding, onArtistClick, imageLoader)
    }

    override fun onBindViewHolder(holder: SingerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: SingerViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_ARTIST_COUNTS)) {
            holder.bindSongCount(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class SingerViewHolder(
        private val binding: ItemSingerCardBinding,
        private val onClick: (ArtistItem) -> Unit,
        private val imageLoader: ((url: String, callback: (android.graphics.Bitmap?) -> Unit) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundArtist: ArtistItem? = null

        init {
            binding.root.setOnClickListener {
                boundArtist?.let(onClick)
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }

        fun bindSongCount(item: ArtistItem) {
            boundArtist = item
            binding.txtSingerSongCount.text = binding.root.context.getString(
                R.string.song_count_only,
                item.songCount,
            )
        }

        fun bind(item: ArtistItem) {
            boundArtist = item
            binding.txtSingerName.text = item.name
            bindSongCount(item)
            binding.txtSingerInitial.text = ArtistInitialResolver.resolve(item.name)
            binding.txtSingerInitial.visibility = View.VISIBLE
            binding.imgSingerAvatar.setImageDrawable(null)
            binding.imgSingerAvatar.visibility = View.GONE

            val url = item.avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
            binding.imgSingerAvatar.tag = url
            if (url != null && imageLoader != null) {
                imageLoader.invoke(url) { bitmap ->
                    if (bitmap != null && binding.imgSingerAvatar.tag == url) {
                        binding.imgSingerAvatar.setImageBitmap(bitmap)
                        binding.imgSingerAvatar.visibility = View.VISIBLE
                        binding.txtSingerInitial.visibility = View.GONE
                    }
                }
            }
        }
    }

    companion object {
        private const val PAYLOAD_ARTIST_COUNTS = "artist_counts"

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ArtistItem>() {
            override fun areItemsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
                oldItem.artistKey == newItem.artistKey

            override fun areContentsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: ArtistItem, newItem: ArtistItem): Any? =
                if (oldItem.artistKey == newItem.artistKey &&
                    oldItem.name == newItem.name &&
                    oldItem.avatarUrl == newItem.avatarUrl &&
                    oldItem.songCount != newItem.songCount
                ) {
                    PAYLOAD_ARTIST_COUNTS
                } else {
                    null
                }
        }
    }
}
