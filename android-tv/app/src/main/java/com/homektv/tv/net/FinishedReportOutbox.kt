package com.homektv.tv.net

/**
 * Keeps finished playback reports until the server acknowledges them.
 *
 * Queue IDs are the idempotency keys. Re-sending an unacknowledged report is
 * safe because the server treats a second completion for the same queue item
 * as already applied.
 */
internal class FinishedReportOutbox(
    initialQueueIds: List<Long> = emptyList(),
    private val persist: (List<Long>) -> Unit = {},
) {
    enum class EnqueueResult {
        ADDED,
        DUPLICATE,
        INVALID,
        FULL,
    }

    private val pending = linkedMapOf<Long, Unit>()

    init {
        PendingFinishedPolicy.sanitize(initialQueueIds)
            .forEach { pending[it] = Unit }
    }

    @Synchronized
    fun enqueue(queueId: Long): EnqueueResult {
        if (queueId <= 0) return EnqueueResult.INVALID
        if (pending.containsKey(queueId)) return EnqueueResult.DUPLICATE
        if (pending.size >= MAX_PENDING) return EnqueueResult.FULL
        pending[queueId] = Unit
        persist(pending.keys.toList())
        return EnqueueResult.ADDED
    }

    /**
     * Sends reports in insertion order. A false transport result stops the
     * batch and leaves the current and remaining reports pending.
     */
    @Synchronized
    fun flush(generation: Long, send: (queueId: Long, generation: Long) -> Boolean): List<Long> {
        if (generation <= 0) return emptyList()
        val sent = mutableListOf<Long>()
        for (queueId in pending.keys.toList()) {
            if (!send(queueId, generation)) break
            sent += queueId
        }
        return sent
    }

    @Synchronized
    fun acknowledge(queueId: Long, status: String) {
        if (status !in TERMINAL_STATUSES) return
        if (pending.remove(queueId) != null) {
            persist(pending.keys.toList())
        }
    }

    /** Kept as an explicit lifecycle hook for reconnect handling. */
    @Synchronized
    fun onDisconnected() = Unit

    @Synchronized
    fun pendingQueueIds(): List<Long> = pending.keys.toList()

    companion object {
        const val MAX_PENDING = PendingFinishedPolicy.MAX_PENDING
        val TERMINAL_STATUSES = setOf("APPLIED", "ALREADY_APPLIED", "STALE")
    }
}
