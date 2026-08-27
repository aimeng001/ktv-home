package com.homektv.tv.net

import android.os.SystemClock

/** Fences playback and upstream reports unless this client owns a live server lease. */
class ActivePlayerLeaseGate(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var active = false
    private var generation = 0L
    private var expiresAtMs = 0L

    @Synchronized
    fun apply(role: String, assignedGeneration: Long, leaseMs: Long) {
        active = role.equals("ACTIVE", ignoreCase = true) && assignedGeneration > 0 && leaseMs > 0
        generation = if (active) assignedGeneration else 0L
        expiresAtMs = if (active) nowMs() + leaseMs else 0L
    }

    @Synchronized
    fun activeGeneration(): Long? {
        if (!active || nowMs() >= expiresAtMs) {
            active = false
            generation = 0L
            return null
        }
        return generation
    }

    @Synchronized
    fun disconnect() {
        active = false
        generation = 0L
        expiresAtMs = 0L
    }
}
