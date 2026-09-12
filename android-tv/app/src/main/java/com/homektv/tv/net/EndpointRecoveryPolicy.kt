package com.homektv.tv.net

class EndpointRecoveryPolicy(private val failureThreshold: Int = 5) {
    private var failureCount = 0

    @Synchronized
    fun onFailure(): Boolean {
        failureCount++
        return failureCount >= failureThreshold
    }

    @Synchronized
    fun onSuccess() {
        failureCount = 0
    }

    fun canAutoMigrate(discoveredCount: Int): Boolean = discoveredCount == 1
}
