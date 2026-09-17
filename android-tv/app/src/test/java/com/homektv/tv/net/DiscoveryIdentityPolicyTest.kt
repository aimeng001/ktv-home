package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryIdentityPolicyTest {
    private val idA = "550e8400-e29b-41d4-a716-446655440000"
    private val idB = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    @Test
    fun unrelatedHigherPriorityResultsDoNotTerminateIdentitySearch() {
        val selected = DiscoveryIdentityPolicy.firstMatchingStage(
            expectedInstanceId = idA,
            stages = listOf(
                listOf(DiscoveredServer("10.0.0.2:8080", "其他", idB)),
                listOf(DiscoveredServer("10.0.0.3:8080", "目标", idA.uppercase())),
            ),
        )

        assertEquals(listOf(DiscoveredServer("10.0.0.3:8080", "目标", idA)), selected)
    }

    @Test
    fun missingExpectedIdentityProducesNoAutomaticTarget() {
        assertEquals(
            emptyList<DiscoveredServer>(),
            DiscoveryIdentityPolicy.firstMatchingStage(
                expectedInstanceId = null,
                stages = listOf(listOf(DiscoveredServer("10.0.0.2:8080", "服务", idA))),
            ),
        )
    }
}
