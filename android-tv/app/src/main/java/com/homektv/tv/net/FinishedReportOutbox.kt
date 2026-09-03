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
    private val pending = linkedMapOf<Long, Unit>()

    init {
        initialQueueIds.asSequence()
            .filter { it > 0 }
            .distinct()
            .take(MAX_PENDING)
            .forEach { pending[it] = Unit }
    }

    @Synchronized
    fun enqueue(queueId: Long): Boolean {
        if (queueId <= 0 || pending.containsKey(queueId) || pending.size >= MAX_PENDING) {
            return false
        }
        pending[queueId] = Unit
        persist(pending.keys.toList())
        return true
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

    private companion object {
        const val MAX_PENDING = 100
        val TERMINAL_STATUSES = setOf("APPLIED", "ALREADY_APPLIED", "STALE")
    }
}
