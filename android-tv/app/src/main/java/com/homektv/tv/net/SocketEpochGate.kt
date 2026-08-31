package com.homektv.tv.net

/**
 * Invalidates callbacks belonging to a replaced or explicitly closed socket.
 * OkHttp callbacks can arrive after a newer socket has already been opened.
 */
internal class SocketEpochGate {
    private var current = 0L

    @Synchronized
    fun begin(): Long {
        current += 1
        return current
    }

    @Synchronized
    fun invalidate(): Long {
        current += 1
        return current
    }

    @Synchronized
    fun isCurrent(epoch: Long): Boolean = current == epoch
}
