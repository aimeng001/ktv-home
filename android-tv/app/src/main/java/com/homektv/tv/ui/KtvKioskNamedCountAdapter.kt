package com.homektv.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.databinding.ItemKioskNamedCountBinding
import com.homektv.tv.net.NamedCount

/** Focusable TV row for language/tag category entries. */
class KtvKioskNamedCountAdapter(
    private val onSelect: (NamedCount) -> Unit,
) : ListAdapter<NamedCount, KtvKioskNamedCountAdapter.ViewHolder>(DIFF_CALLBACK) {

    @DrawableRes
    private var iconResource = R.drawable.ic_category

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemKioskNamedCountBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        ).apply {
            binding.namedCountRoot.setOnClickListener {
                val item = it.tag as? NamedCount ?: return@setOnClickListener
                onSelect(item)
            }
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.namedCountRoot.tag = item
        holder.binding.imgNamedCountIcon.setImageResource(iconResource)
        holder.binding.txtNamedCountName.text = item.name
        holder.binding.txtNamedCountSongs.text = holder.binding.root.context.getString(
            R.string.named_count_songs,
            item.songCount,
        )
        holder.binding.namedCountRoot.contentDescription = holder.binding.root.context.getString(
            R.string.named_count_selection_description,
            item.name,
        )
    }

    fun setIconResource(@DrawableRes iconRes: Int) {
        if (iconResource == iconRes) return
        iconResource = iconRes
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    class ViewHolder(val binding: ItemKioskNamedCountBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NamedCount>() {
            override fun areItemsTheSame(oldItem: NamedCount, newItem: NamedCount): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: NamedCount, newItem: NamedCount): Boolean =
                oldItem == newItem
        }
    }
}
