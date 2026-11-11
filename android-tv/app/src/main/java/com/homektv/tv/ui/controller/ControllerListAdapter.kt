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

    private data class PendingSubmission<T : Any>(
        val generation: Int,
        val replacement: List<T>,
        val commitCallback: Runnable?,
    )

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
    /** At most one submitted diff is running and one newer list is retained. Main-thread owned. */
    private var diffInFlight = false
    private var pendingSubmission: PendingSubmission<T>? = null

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
            pendingSubmission = null
            commitCallback?.run()
            return
        }

        if (replacement.isEmpty()) {
            pendingSubmission = null
            val count = previous.size
            items = emptyList()
            if (RemovalPolicy.shouldNotifyRemoval(count)) {
                safeNotify { notifyItemRangeRemoved(0, count) }
            }
            commitCallback?.run()
            return
        }

        if (previous.isEmpty()) {
            pendingSubmission = null
            items = replacement
            safeNotify { notifyItemRangeInserted(0, replacement.size) }
            commitCallback?.run()
            return
        }

        // New snapshots supersede queued snapshots; never enqueue one worker task per render.
        pendingSubmission = PendingSubmission(generation, replacement, commitCallback)
        startNextDiffIfIdle()
    }

    private fun startNextDiffIfIdle() {
        if (diffInFlight) return
        val submission = pendingSubmission ?: return
        pendingSubmission = null

        // Read the applied list when work starts. A stale in-flight diff is not applied, so the
        // next diff must be based on the list RecyclerView currently displays.
        val previous = items
        if (previous.isEmpty()) {
            items = submission.replacement
            safeNotify { notifyItemRangeInserted(0, submission.replacement.size) }
            submission.commitCallback?.run()
            startNextDiffIfIdle()
            return
        }

        diffInFlight = true
        try {
            bgExecutor.execute {
                val diffResult = try {
                    DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                        override fun getOldListSize(): Int = previous.size
                        override fun getNewListSize(): Int = submission.replacement.size
                        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                            itemId(previous[oldItemPosition]) == itemId(submission.replacement[newItemPosition])

                        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                            contentsSame(previous[oldItemPosition], submission.replacement[newItemPosition])
                    })
                } catch (failure: Throwable) {
                    runOnMainThread {
                        diffInFlight = false
                        startNextDiffIfIdle()
                    }
                    throw failure
                }

                runOnMainThread {
                    try {
                        if (maxScheduledGeneration == submission.generation) {
                            items = submission.replacement
                            safeNotify { diffResult.dispatchUpdatesTo(this) }
                            submission.commitCallback?.run()
                        }
                    } finally {
                        diffInFlight = false
                        startNextDiffIfIdle()
                    }
                }
            }
        } catch (failure: Throwable) {
            diffInFlight = false
            if (pendingSubmission == null && maxScheduledGeneration == submission.generation) {
                pendingSubmission = submission
            }
            throw failure
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
