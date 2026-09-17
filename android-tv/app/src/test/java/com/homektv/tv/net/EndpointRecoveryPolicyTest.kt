package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRecoveryPolicyTest {

    @Test
    fun failureCountBelowThresholdDoesNotTriggerDiscovery() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { assertFalse(policy.onFailure(nowMs = it.toLong())) }
    }

    @Test
    fun reachingThresholdTriggersDiscovery() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { policy.onFailure(nowMs = it.toLong()) }
        assertTrue(policy.onFailure(nowMs = 4L))
    }

    @Test
    fun successResetsCounter() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { policy.onFailure(nowMs = it.toLong()) }
        policy.onSuccess()
        assertFalse(policy.onFailure(nowMs = 5L))
    }

    @Test
    fun discoveryTriggerUsesIndependentExponentialBackoff() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 2)
        assertFalse(policy.onFailure(nowMs = 0L))
        assertTrue(policy.onFailure(nowMs = 0L))
        assertFalse(policy.onFailure(nowMs = 29_999L))
        assertTrue(policy.onFailure(nowMs = 30_000L))
        assertFalse(policy.onFailure(nowMs = 89_999L))
        assertTrue(policy.onFailure(nowMs = 90_000L))
    }

    @Test
    fun reconnectSuccessResetsDiscoveryBackoff() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 1)
        assertTrue(policy.onFailure(nowMs = 0L))
        policy.onSuccess()
        assertTrue(policy.onFailure(nowMs = 1L))
    }
}
