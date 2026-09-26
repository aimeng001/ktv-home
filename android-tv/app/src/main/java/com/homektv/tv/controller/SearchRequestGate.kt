package com.homektv.tv.controller

/** Rejects out-of-order search responses, including A → B → A query sequences. */
internal class SearchRequestGate {
    class Ticket internal constructor(val generation: Long, val query: String)

    private var generation = 0L
    private var currentQuery = ""

    @Synchronized
    fun begin(query: String): Ticket {
        generation += 1L
        currentQuery = query
        return Ticket(generation, query)
    }

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean =
        ticket.generation == generation && ticket.query == currentQuery
}
