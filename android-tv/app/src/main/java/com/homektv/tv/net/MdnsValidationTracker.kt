package com.homektv.tv.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks in-flight mDNS endpoint validation jobs so discovery awaits recent
 * resolutions up to a bounded grace period and cancels stragglers on exit.
 */
class MdnsValidationTracker {
    private val lock = Any()
    private val jobs = mutableListOf<Job>()
    private var closed = false

    fun track(job: Job) {
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                jobs.add(job)
                true
            }
        }
        if (!accepted) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                jobs.remove(job)
            }
        }
    }

    suspend fun awaitAndCancelRemaining(gracePeriodMs: Long) {
        val snapshot = synchronized(lock) { jobs.toList() }
        try {
            if (snapshot.isNotEmpty() && gracePeriodMs > 0L) {
                withTimeoutOrNull(gracePeriodMs) {
                    snapshot.joinAll()
                }
            }
        } finally {
            val remaining = synchronized(lock) {
                closed = true
                jobs.toList().also { jobs.clear() }
            }
            remaining.forEach { it.cancel() }
        }
    }

    fun activeCount(): Int = synchronized(lock) { jobs.size }
}
