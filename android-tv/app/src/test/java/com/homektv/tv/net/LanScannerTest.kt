package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScannerTest {
    @Test
    fun scansMultipleCommonServerPorts() {
        assertEquals(listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090), LanScanner.CANDIDATE_PORTS)
    }

    @Test
    fun validationRequiresHealthAndDatabaseReadiness() {
        assertEquals(listOf("/api/health", "/api/ready"), LanScanner.VALIDATION_PATHS)
    }

    @Test
    fun validationRejectsMissingOrDownReadiness() {
        val scanner = LanScanner()
        try {
            assertTrue(!scanner.validationSatisfied(mapOf("/api/health" to true)))
            assertTrue(
                !scanner.validationSatisfied(
                    mapOf("/api/health" to true, "/api/ready" to false),
                ),
            )
            assertTrue(
                scanner.validationSatisfied(
                    mapOf("/api/health" to true, "/api/ready" to true),
                ),
            )
        } finally {
            scanner.close()
        }
    }

    @Test
    fun identityValidationRejectsAHealthyDifferentServer() {
        val expected = "550e8400-e29b-41d4-a716-446655440000"
        val healthy = "{\"service\":\"home-ktv\",\"instanceId\":\"$expected\"}"
        val different = "{\"service\":\"home-ktv\",\"instanceId\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\"}"

        assertTrue(LanScanner.identityValidationSatisfied(healthy, expected))
        assertTrue(!LanScanner.identityValidationSatisfied(different, expected))
        assertTrue(!LanScanner.identityValidationSatisfied("{\"service\":\"home-ktv\"}", expected))
        assertTrue(!LanScanner.identityValidationSatisfied(healthy, "not-a-uuid"))
    }

    @Test
    fun subnetHealthProbeCarriesStableServerIdentity() {
        val instanceId = "550e8400-e29b-41d4-a716-446655440000"

        val discovered = LanScanner.parseDiscoveredServer(
            hostPort = "192.168.1.10:8080",
            healthPayload = "{\"service\":\"home-ktv\",\"instanceId\":\"$instanceId\"}",
            readyPayload = "{\"service\":\"home-ktv\",\"status\":\"UP\"}",
        )

        assertEquals(instanceId, discovered?.instanceId)
    }

    @Test
    fun malformedExplicitHealthIdentityIsNotDowngradedToUnknownServer() {
        assertNull(
            LanScanner.parseDiscoveredServer(
                hostPort = "192.168.1.10:8080",
                healthPayload = "{\"service\":\"home-ktv\",\"instanceId\":\"not-a-uuid\"}",
                readyPayload = "{\"service\":\"home-ktv\",\"status\":\"UP\"}",
            ),
        )
    }

    @Test
    fun prioritizesDefaultPortAcrossSubnet() {
        val targets = LanScanner().scanTargets("192.168.1.")

        assertEquals(LanScanner.CANDIDATE_PORTS.size * 254, targets.size)
        assertEquals("192.168.1.1:8080", targets.first())
        assertEquals("192.168.1.254:8080", targets[253])
        assertTrue(targets.contains("192.168.1.10:8888"))
        assertEquals(targets.size, targets.distinct().size)
    }
}
