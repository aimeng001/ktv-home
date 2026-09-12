package com.homektv.tv.net

import org.junit.Assert.assertEquals
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
    fun prioritizesDefaultPortAcrossSubnet() {
        val targets = LanScanner().scanTargets("192.168.1.")

        assertEquals(LanScanner.CANDIDATE_PORTS.size * 254, targets.size)
        assertEquals("192.168.1.1:8080", targets.first())
        assertEquals("192.168.1.254:8080", targets[253])
        assertTrue(targets.contains("192.168.1.10:8888"))
        assertEquals(targets.size, targets.distinct().size)
    }
}
