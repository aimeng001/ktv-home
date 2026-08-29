package com.homektv.tv.player

internal data class PlaybackRetryTicket(
    val generation: Long,
    val request: PlaybackRequestIdentity,
)

/** Prevents a delayed retry for an old media request from touching a newer one. */
internal class PlaybackRetryGate {
    private var generation = 0L

    fun begin(request: PlaybackRequestIdentity): PlaybackRetryTicket {
        generation += 1
        return PlaybackRetryTicket(generation, request)
    }

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(ticket: PlaybackRetryTicket, request: PlaybackRequestIdentity?): Boolean =
        ticket.generation == generation && ticket.request == request
}
