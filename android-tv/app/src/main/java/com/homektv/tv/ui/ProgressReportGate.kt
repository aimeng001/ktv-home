package com.homektv.tv.ui

/** Bounds network progress reports by elapsed wall time, while letting a new queue item report immediately. */
internal class ProgressReportGate(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastQueueId: Long? = null
    private var lastReportedAtMs: Long? = null

    init {
        require(intervalMs > 0L) { "intervalMs must be positive" }
    }

    fun shouldReport(queueId: Long?, elapsedRealtimeMs: Long): Boolean {
        val lastAt = lastReportedAtMs ?: return true
        if (queueId != lastQueueId) return true
        return elapsedRealtimeMs >= lastAt && elapsedRealtimeMs - lastAt >= intervalMs
    }

    fun markReported(queueId: Long?, elapsedRealtimeMs: Long) {
        lastQueueId = queueId
        lastReportedAtMs = elapsedRealtimeMs
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}
