package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanScannerTest {
    @Test
    fun manualValidationAllowsHealthAndReadinessResponsesSlowerThanSubnetProbeBudget() = runBlocking {
        val server = delayedHomeKtvServer(delayMs = 375)
        val scanner = LanScanner()
        try {
            val discovered = scanner.discover("127.0.0.1:" + server.localPort)

            assertNotNull(discovered)
            assertEquals("Delayed fixture", discovered?.name)
        } finally {
            scanner.close()
            server.close()
        }
    }

    @Test
    fun subnetProbeKeepsTheShortTimeoutForTheSameDelayedServer() = runBlocking {
        val server = delayedHomeKtvServer(delayMs = 375)
        val scanner = LanScanner()
        try {
            val startedAt = System.nanoTime()
            val discovered = scanner.discoverForScan("127.0.0.1:" + server.localPort)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertNull(discovered)
            assertTrue("subnet probe exceeded its short timeout: " + elapsedMs + "ms", elapsedMs < 1_500)
        } finally {
            scanner.close()
            server.close()
        }
    }

    @Test
    fun cancellingManualValidationCancelsTheInFlightHttpCall() = runBlocking {
        val requestReceived = CountDownLatch(1)
        val server = delayedHomeKtvServer(delayMs = 2_000, requestReceived = requestReceived)
        val scanner = LanScanner()
        try {
            val validation = async(Dispatchers.IO) {
                scanner.discover("127.0.0.1:" + server.localPort)
            }
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            validation.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("HTTP validation was not cancelled promptly: " + elapsedMs + "ms", elapsedMs < 500)
        } finally {
            scanner.close()
            server.close()
        }
    }

    @Test
    fun scansMultipleCommonServerPorts() {
        assertEquals(listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090, 54001), LanScanner.CANDIDATE_PORTS)
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
    fun includesDockerNasPresetTargetInScanTargets() {
        assertTrue(LanScanner.PRESET_TARGETS.contains("192.168.31.18:54001"))

        val emptyPrefixTargets = LanScanner().scanTargets(null)
        assertEquals(LanScanner.PRESET_TARGETS, emptyPrefixTargets)

        val targets = LanScanner().scanTargets("192.168.1.")
        assertEquals("192.168.31.18:54001", targets.first())
        assertTrue(targets.contains("192.168.31.18:54001"))
        assertTrue(targets.contains("192.168.1.1:54001"))
        assertEquals(LanScanner.PRESET_TARGETS.size + LanScanner.CANDIDATE_PORTS.size * 254, targets.size)
    }

    @Test
    fun prioritizesDefaultPortAcrossSubnet() {
        val targets = LanScanner().scanTargets("192.168.1.")

        assertEquals(LanScanner.PRESET_TARGETS.size + LanScanner.CANDIDATE_PORTS.size * 254, targets.size)
        assertTrue(targets.contains("192.168.31.18:54001"))
        assertTrue(targets.contains("192.168.1.1:8080"))
        assertTrue(targets.contains("192.168.1.1:54001"))
        assertTrue(targets.contains("192.168.1.254:8080"))
        assertTrue(targets.contains("192.168.1.10:8888"))
        assertEquals(targets.size, targets.distinct().size)
    }

    @Test
    fun prioritizesPresetTargetOnMatchingSubnetWithoutDuplicates() {
        val targets = LanScanner().scanTargets("192.168.31.")

        assertEquals("192.168.31.18:54001", targets.first())
        assertTrue(targets.contains("192.168.31.18:54001"))
        assertTrue(targets.contains("192.168.31.1:8080"))
        assertTrue(targets.contains("192.168.31.1:54001"))
        assertEquals(targets.size, targets.distinct().size)
        assertEquals(LanScanner.CANDIDATE_PORTS.size * 254, targets.size)
    }

    private fun delayedHomeKtvServer(
        delayMs: Long,
        requestReceived: CountDownLatch? = null,
    ): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread({
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream()
                    repeat(2) {
                        val requestLine = input.readLine() ?: return@Thread
                        while (input.readLine()?.isEmpty() == false) {
                            // Consume request headers before writing the response.
                        }
                        requestReceived?.countDown()
                        Thread.sleep(delayMs)
                        val body = if (requestLine.contains("/api/health")) {
                            """{"service":"home-ktv","name":"Delayed fixture","instanceId":"550e8400-e29b-41d4-a716-446655440000"}"""
                        } else {
                            """{"service":"home-ktv","status":"UP"}"""
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: keep-alive\r\n\r\n")
                                .toByteArray(Charsets.UTF_8),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
                // The current short-timeout client is expected to close the socket in RED.
            }
        }, "lan-scanner-delayed-http-fixture").apply { isDaemon = true }.start()
        return server
    }
}
