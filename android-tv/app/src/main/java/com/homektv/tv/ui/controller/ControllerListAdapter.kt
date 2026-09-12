package com.homektv.tv.ui.controller

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Asynchronous ListAdapter used by the controller panels. Diffing is offloaded
 * to a background thread pool, while RecyclerView keeps row creation proportional
 * to the visible viewport. Stable ids are deliberately owned by the caller
 * (queue id, song id, or a deterministic synthetic id).
 */
class ControllerListAdapter<T : Any>(
    private val itemId: (T) -> Long,
    private val createView: (ViewGroup, Int) -> View,
    private val bindView: (View, T, Int) -> Unit,
    private val contentsSame: (T, T) -> Boolean = { old, new -> old == new },
    private val viewType: (T) -> Int = { 0 },
    private val backgroundExecutor: Executor? = null,
    private val mainThreadExecutor: Executor? = null,
) : RecyclerView.Adapter<ControllerListAdapter<T>.RowHolder>() {

    constructor(
        itemId: (T) -> Long,
        createView: (ViewGroup) -> View,
        bindView: (View, T, Int) -> Unit,
        contentsSame: (T, T) -> Boolean = { old, new -> old == new },
    ) : this(
        itemId = itemId,
        createView = { parent, _ -> createView(parent) },
        bindView = bindView,
        contentsSame = contentsSame,
        viewType = { 0 },
        backgroundExecutor = null,
        mainThreadExecutor = null,
    )

    private val bgExecutor: Executor = backgroundExecutor ?: DEFAULT_BG_EXECUTOR
    private var maxScheduledGeneration = 0
    private var items: List<T> = emptyList()

    init {
        try {
            setHasStableIds(true)
        } catch (_: Throwable) {
            // Android stub jar in unit tests has uninitialized Observable.mObservers
        }
    }

    val currentList: List<T>
        get() = items

    fun submitList(next: List<T>?, commitCallback: Runnable? = null) {
        val generation = ++maxScheduledGeneration
        val replacement = next?.toList() ?: emptyList()
        val previous = items

        if (replacement === previous) {
            commitCallback?.run()
            return
        }

        if (replacement.isEmpty()) {
            val count = previous.size
            items = emptyList()
            if (RemovalPolicy.shouldNotifyRemoval(count)) {
                safeNotify { notifyItemRangeRemoved(0, count) }
            }
            commitCallback?.run()
            return
        }

        if (previous.isEmpty()) {
            items = replacement
            safeNotify { notifyItemRangeInserted(0, replacement.size) }
            commitCallback?.run()
            return
        }

        bgExecutor.execute {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = replacement.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    itemId(previous[oldItemPosition]) == itemId(replacement[newItemPosition])

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    contentsSame(previous[oldItemPosition], replacement[newItemPosition])
            })

            runOnMainThread {
                if (maxScheduledGeneration == generation) {
                    items = replacement
                    safeNotify { diffResult.dispatchUpdatesTo(this) }
                    commitCallback?.run()
                }
            }
        }
    }

    private fun safeNotify(action: () -> Unit) {
        try {
            action()
        } catch (_: Throwable) {
            // Unit tests without mocked adapter observers
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = itemId(items[position])

    override fun getItemViewType(position: Int): Int = viewType(items[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(createView(parent, viewType))

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        bindView(holder.itemView, items[position], position)
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (mainThreadExecutor != null) {
            mainThreadExecutor.execute(action)
            return
        }
        try {
            val looper = android.os.Looper.getMainLooper()
            if (looper != null && android.os.Looper.myLooper() == looper) {
                action()
            } else if (looper != null) {
                MAIN_HANDLER?.post(action) ?: action()
            } else {
                action()
            }
        } catch (_: Throwable) {
            action()
        }
    }

    inner class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        val RemovalPolicy = ControllerListRemovalPolicy

        private val DEFAULT_BG_EXECUTOR = Executors.newFixedThreadPool(2)
        private val MAIN_HANDLER by lazy {
            try {
                android.os.Looper.getMainLooper()?.let { android.os.Handler(it) }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

object ControllerListRemovalPolicy {
    fun shouldNotifyRemoval(count: Int): Boolean = count > 0
}
