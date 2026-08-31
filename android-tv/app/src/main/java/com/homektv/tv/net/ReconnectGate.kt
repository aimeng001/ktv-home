package com.homektv.tv.net

/** Ensures a disconnect storm can schedule at most one reconnect callback. */
internal class ReconnectGate {
    private var scheduled = false

    @Synchronized
    fun trySchedule(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized
    fun markRun() {
        scheduled = false
    }
}
