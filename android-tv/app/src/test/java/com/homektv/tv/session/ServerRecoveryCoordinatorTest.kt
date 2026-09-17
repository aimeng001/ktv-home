package com.homektv.tv.session

import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRecoveryCoordinatorTest {
    private val instanceA = "550e8400-e29b-41d4-a716-446655440000"
    private val instanceB = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    @Test
    fun sameIdentityAtNewEndpointProducesCandidateWithoutChangingSession() {
        val current = SavedServer("10.0.0.10:8080", "客厅", instanceA)
        val reported = mutableListOf<DiscoveredServer>()
        val coordinator = ServerRecoveryCoordinator(
            currentServer = current,
            onCandidate = { reported += it },
        )
        val candidate = DiscoveredServer("10.0.0.25:8080", "客厅KTV", instanceA)

        val action = coordinator.reportDiscovered(candidate)

        assertEquals(RecoveryAction.PROMPT_RESTART, action)
        assertEquals(listOf(candidate), reported)
        assertEquals("10.0.0.10:8080", current.hostPort)
    }

    @Test
    fun differentIdentityIsIgnoredEvenAtAnotherEndpoint() {
        val reported = mutableListOf<DiscoveredServer>()
        val coordinator = ServerRecoveryCoordinator(
            currentServer = SavedServer("10.0.0.10:8080", "当前服务端", instanceA),
            onCandidate = { reported += it },
        )

        val action = coordinator.reportDiscovered(
            DiscoveredServer("10.0.0.25:8080", "替代服务端", instanceB),
        )

        assertTrue(action == RecoveryAction.IGNORE)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun missingCurrentIdentityDisablesAutomaticRecovery() {
        val reported = mutableListOf<DiscoveredServer>()
        val coordinator = ServerRecoveryCoordinator(
            currentServer = SavedServer("10.0.0.10:8080", "旧服务端"),
            onCandidate = { reported += it },
        )

        val action = coordinator.reportDiscovered(
            DiscoveredServer("10.0.0.25:8080", "同名服务端", instanceA),
        )

        assertEquals(RecoveryAction.IGNORE, action)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun batchSkipsUnrelatedServersUntilMatchingIdentityIsFound() {
        val reported = mutableListOf<DiscoveredServer>()
        val coordinator = ServerRecoveryCoordinator(
            currentServer = SavedServer("10.0.0.10:8080", "当前服务端", instanceA),
            onCandidate = { reported += it },
        )
        val unrelated = DiscoveredServer("10.0.0.20:8080", "其他", instanceB)
        val replacement = DiscoveredServer("10.0.0.25:8080", "当前服务端", instanceA)

        val action = coordinator.reportDiscovered(listOf(unrelated, replacement))

        assertEquals(RecoveryAction.PROMPT_RESTART, action)
        assertEquals(listOf(replacement), reported)
    }
}
