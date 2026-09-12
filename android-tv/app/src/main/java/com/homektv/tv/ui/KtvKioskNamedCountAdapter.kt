package com.homektv.tv.ui

import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.net.NamedCount

/** Focusable TV row for language/tag category entries. */
class KtvKioskNamedCountAdapter(
    private val onSelect: (NamedCount) -> Unit,
) : ListAdapter<NamedCount, KtvKioskNamedCountAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(Button(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minHeight = 56
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                val item = tag as? NamedCount ?: return@setOnClickListener
                onSelect(item)
            }
        })

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.button.tag = item
        holder.button.text = "${item.name}（${item.songCount} 首）"
        holder.button.contentDescription = "选择${item.name}"
    }

    class ViewHolder(val button: Button) : RecyclerView.ViewHolder(button)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NamedCount>() {
            override fun areItemsTheSame(oldItem: NamedCount, newItem: NamedCount): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: NamedCount, newItem: NamedCount): Boolean =
                oldItem == newItem
        }
    }
}
