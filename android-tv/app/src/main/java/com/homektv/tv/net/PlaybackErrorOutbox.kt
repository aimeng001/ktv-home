package com.homektv.tv.net

import kotlinx.serialization.Serializable

/** A replayable play_error identified by the queue item it is meant to advance. */
@Serializable
data class PendingPlaybackError(
    val queueId: Long,
    val fileId: Long? = null,
    val message: String = "媒体播放失败",
)

/**
 * Keeps play-error reports until the server acknowledges them. This closes the
 * disconnect window between local source exhaustion and the server-side queue
 * transition. Replays are safe because the server rejects a stale queue id.
 */
internal class PlaybackErrorOutbox(
    initialErrors: List<PendingPlaybackError> = emptyList(),
    private val persist: (List<PendingPlaybackError>) -> Unit = {},
) {
    enum class EnqueueResult {
        ADDED,
        DUPLICATE,
        INVALID,
        FULL,
    }

    private val pending = linkedMapOf<Long, PendingPlaybackError>()

    init {
        sanitize(initialErrors).forEach { pending[it.queueId] = it }
    }

    @Synchronized
    fun enqueue(error: PendingPlaybackError): EnqueueResult {
        val normalized = normalize(error) ?: return EnqueueResult.INVALID
        if (pending.containsKey(normalized.queueId)) return EnqueueResult.DUPLICATE
        if (pending.size >= MAX_PENDING) return EnqueueResult.FULL
        pending[normalized.queueId] = normalized
        persist(pending.values.toList())
        return EnqueueResult.ADDED
    }

    /** Sends in insertion order; a failed transport leaves the event pending. */
    @Synchronized
    fun flush(
        generation: Long,
        send: (error: PendingPlaybackError, generation: Long) -> Boolean,
    ): List<Long> {
        if (generation <= 0) return emptyList()
        val sent = mutableListOf<Long>()
        for (error in pending.values.toList()) {
            if (!send(error, generation)) break
            sent += error.queueId
        }
        return sent
    }

    @Synchronized
    fun acknowledge(queueId: Long, status: String) {
        if (status !in TERMINAL_STATUSES) return
        if (pending.remove(queueId) != null) persist(pending.values.toList())
    }

    @Synchronized
    fun pendingErrors(): List<PendingPlaybackError> = pending.values.toList()

    companion object {
        const val MAX_PENDING = PendingFinishedPolicy.MAX_PENDING
        const val MAX_MESSAGE_CHARS = 256
        val TERMINAL_STATUSES = setOf("APPLIED", "ALREADY_APPLIED", "STALE")

        fun sanitize(errors: Iterable<PendingPlaybackError>): List<PendingPlaybackError> = errors
            .mapNotNull(::normalize)
            .distinctBy(PendingPlaybackError::queueId)
            .take(MAX_PENDING)

        fun merge(primary: Iterable<PendingPlaybackError>, secondary: Iterable<PendingPlaybackError>): List<PendingPlaybackError> =
            sanitize(primary.asSequence().plus(secondary.asSequence()).asIterable())

        private fun normalize(error: PendingPlaybackError): PendingPlaybackError? {
            if (error.queueId <= 0) return null
            val boundedMessage = error.message.ifBlank { "媒体播放失败" }.take(MAX_MESSAGE_CHARS)
            return error.copy(
                fileId = error.fileId?.takeIf { it > 0 },
                message = boundedMessage,
            )
        }
    }
}