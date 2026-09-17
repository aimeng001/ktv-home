package com.homektv.tv.net

class EndpointRecoveryPolicy(private val failureThreshold: Int = 5) {
    private var failureCount = 0
    private var discoveryAttempts = 0
    private var nextDiscoveryAtMs = Long.MIN_VALUE

    @Synchronized
    fun onFailure(nowMs: Long): Boolean {
        failureCount++
        if (failureCount < failureThreshold || nowMs < nextDiscoveryAtMs) return false
        val delay = DISCOVERY_BACKOFF_MS[discoveryAttempts.coerceAtMost(DISCOVERY_BACKOFF_MS.lastIndex)]
        discoveryAttempts++
        nextDiscoveryAtMs = nowMs + delay
        return true
    }

    @Synchronized
    fun onSuccess() {
        failureCount = 0
        discoveryAttempts = 0
        nextDiscoveryAtMs = Long.MIN_VALUE
    }

    companion object {
        private val DISCOVERY_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    }
}
