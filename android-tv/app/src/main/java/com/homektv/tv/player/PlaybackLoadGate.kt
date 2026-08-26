package com.homektv.tv.player

internal data class PlaybackLoadTicket(
    val generation: Long,
    val queueId: Long?,
)

/** Generation gate for cancellable Activity-side metadata/media loads. */
internal class PlaybackLoadGate {
    private var generation = 0L
    private var currentQueueId: Long? = null

    fun begin(queueId: Long?): PlaybackLoadTicket {
        generation += 1
        currentQueueId = queueId
        return PlaybackLoadTicket(generation, queueId)
    }

    fun invalidate() {
        generation += 1
        currentQueueId = null
    }

    fun isCurrent(ticket: PlaybackLoadTicket): Boolean =
        ticket.generation == generation && ticket.queueId == currentQueueId
}
