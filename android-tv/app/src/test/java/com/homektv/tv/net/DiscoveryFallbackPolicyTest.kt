package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryFallbackPolicyTest {
    @Test
    fun aValidHigherPriorityHitSkipsLowerPriorityScans() {
        assertFalse(DiscoveryFallbackPolicy.shouldRunFallback(foundCount = 1))
        assertTrue(DiscoveryFallbackPolicy.shouldRunFallback(foundCount = 0))
    }
}
