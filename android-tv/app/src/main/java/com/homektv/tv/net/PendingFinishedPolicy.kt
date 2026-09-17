package com.homektv.tv.net

/** One bounded invariant shared by the in-memory and persisted completion queues. */
internal object PendingFinishedPolicy {
    const val MAX_PENDING = 1_000

    fun sanitize(ids: Iterable<Long>): List<Long> = ids.asSequence()
        .filter { it > 0L }
        .distinct()
        .take(MAX_PENDING)
        .toList()

    fun merge(primary: Iterable<Long>, secondary: Iterable<Long>): List<Long> =
        sanitize(primary.asSequence().plus(secondary.asSequence()).asIterable())
}
