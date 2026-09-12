package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRecoveryPolicyTest {

    @Test
    fun failureCountBelowThresholdDoesNotTriggerDiscovery() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { assertFalse(policy.onFailure()) }
    }

    @Test
    fun reachingThresholdTriggersDiscovery() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { policy.onFailure() }
        assertTrue(policy.onFailure())
    }

    @Test
    fun successResetsCounter() {
        val policy = EndpointRecoveryPolicy(failureThreshold = 5)
        repeat(4) { policy.onFailure() }
        policy.onSuccess()
        assertFalse(policy.onFailure())
    }

    @Test
    fun canOnlyAutoMigrateWhenExactlyOneServerFound() {
        val policy = EndpointRecoveryPolicy()
        assertTrue(policy.canAutoMigrate(discoveredCount = 1))
        assertFalse(policy.canAutoMigrate(discoveredCount = 0))
        assertFalse(policy.canAutoMigrate(discoveredCount = 2))
    }
}
