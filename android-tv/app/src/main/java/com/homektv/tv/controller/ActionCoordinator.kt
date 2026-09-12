package com.homektv.tv.controller

/** Identity of one in-flight write.  Different resources must not block each other. */
data class ActionKey(val kind: String, val resourceId: Long = 0L)

/**
 * Thread-confined coordinator used by the ViewModel.  It intentionally stores
 * no durable queue state: the server snapshot remains the source of truth.
 */
class ActionCoordinator {
    private val pending = linkedSetOf<ActionKey>()

    fun tryStart(key: ActionKey): Boolean = synchronized(pending) {
        if (!pending.add(key)) false else true
    }

    fun finish(key: ActionKey) {
        synchronized(pending) { pending.remove(key) }
    }

    fun snapshot(): Set<ActionKey> = synchronized(pending) { pending.toSet() }
}
