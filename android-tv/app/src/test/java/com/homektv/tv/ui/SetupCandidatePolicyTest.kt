package com.homektv.tv.ui

import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCandidatePolicyTest {
    @Test
    fun manualSubmitKeepsDiscoveredInstanceIdentityForMatchingHost() {
        val candidate = DiscoveredServer(
            hostPort = "nas-a:8080",
            name = "客厅",
            instanceId = "550e8400-e29b-41d4-a716-446655440000",
        )

        val server = SetupCandidatePolicy.resolve("nas-a:8080", candidate)

        assertEquals(candidate.instanceId, server.instanceId)
        assertEquals(candidate.name, server.name)
    }

    @Test
    fun manualSubmitOutsideCandidateDoesNotBorrowItsIdentity() {
        val candidate = DiscoveredServer(
            hostPort = "nas-a:8080",
            name = "客厅",
            instanceId = "550e8400-e29b-41d4-a716-446655440000",
        )

        val server = SetupCandidatePolicy.resolve("nas-b:8080", candidate)

        assertEquals(null, server.instanceId)
        assertEquals("nas-b:8080", server.hostPort)
    }

    @Test
    fun manualSubmitForKnownHostPreservesSavedInstanceIdentity() {
        val saved = SavedServer(
            hostPort = "nas-a:8080",
            name = "客厅",
            instanceId = "550e8400-e29b-41d4-a716-446655440000",
        )

        val server = SetupCandidatePolicy.resolve("nas-a:8080", candidate = null, knownServer = saved)

        assertEquals(saved.instanceId, server.instanceId)
        assertEquals(saved.name, server.name)
    }

    @Test
    fun unchangedCredentialIsNotReusedAcrossDifferentServerIdentity() {
        val current = SavedServer(
            hostPort = "nas-a:8080",
            name = "旧服务",
            instanceId = "550e8400-e29b-41d4-a716-446655440000",
        )
        val target = current.copy(
            name = "新服务",
            instanceId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        )

        assertFalse(SetupCandidatePolicy.shouldReuseInitialCredential(current, target, credentialChanged = false))
        assertTrue(SetupCandidatePolicy.shouldReuseInitialCredential(current, target, credentialChanged = true))
        assertTrue(SetupCandidatePolicy.shouldReuseInitialCredential(current, current, credentialChanged = false))
    }

    @Test
    fun legacyHostDoesNotMigrateUnchangedCredentialToAFirstIdentityWithoutProof() {
        val legacy = SavedServer("nas-a:8080", "旧服务")
        val identified = legacy.copy(instanceId = "550e8400-e29b-41d4-a716-446655440000")

        assertFalse(SetupCandidatePolicy.shouldReuseInitialCredential(legacy, identified, credentialChanged = false))
        assertTrue(
            SetupCandidatePolicy.shouldReuseInitialCredential(
                legacy, identified, credentialChanged = false, legacyMigrationAccepted = true,
            ),
        )
    }

    @Test
    fun legacyHostDoesNotMigrateUnchangedCredentialToDifferentHost() {
        val legacy = SavedServer("nas-a:8080", "旧服务")
        val differentHost = SavedServer("nas-b:8080", "旧服务", instanceId = "550e8400-e29b-41d4-a716-446655440000")

        assertFalse(SetupCandidatePolicy.shouldReuseInitialCredential(legacy, differentHost, credentialChanged = false))
    }
}
