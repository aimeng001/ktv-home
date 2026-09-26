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

    class SingerViewHolder(
        private val binding: ItemSingerCardBinding,
        private val onClick: (ArtistItem) -> Unit,
        private val imageLoader: ((url: String, callback: (android.graphics.Bitmap?) -> Unit) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ArtistItem) {
            binding.txtSingerName.text = item.name
            binding.txtSingerSongCount.text = binding.root.context.getString(
                R.string.song_count_only,
                item.songCount,
            )
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

            binding.root.setOnClickListener {
                onClick(item)
            }

            // TV 焦点缩放动效
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ArtistItem>() {
            override fun areItemsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
                oldItem.artistKey == newItem.artistKey

            override fun areContentsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
                oldItem == newItem
        }
    }
}
