package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityPolicyTest {
    private val sameId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun sameInstanceMayOfferEndpointReplacement() {
        assertTrue(
            ServerIdentityPolicy.isSameServer(
                SavedServer("10.0.0.10:8080", "客厅", sameId),
                DiscoveredServer("10.0.0.25:8080", "客厅", sameId),
            ),
        )
    }

    @Test
    fun differentInstanceCannotOfferSilentReplacement() {
        assertFalse(
            ServerIdentityPolicy.isSameServer(
                SavedServer("10.0.0.10:8080", "客厅", sameId),
                DiscoveredServer("10.0.0.25:8080", "卧室", "6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            ),
        )
    }

    @Test
    fun legacyServerWithoutIdentityRequiresManualConfirmation() {
        assertFalse(
            ServerIdentityPolicy.isSameServer(
                SavedServer("10.0.0.10:8080", "旧服务端"),
                DiscoveredServer("10.0.0.25:8080", "旧服务端"),
            ),
        )
    }

    @Test
    fun sameInstanceComparisonNormalizesUuidCase() {
        assertTrue(
            ServerIdentityPolicy.isSameServer(
                SavedServer("10.0.0.10:8080", "客厅", sameId.uppercase()),
                DiscoveredServer("10.0.0.25:8080", "客厅", sameId),
            ),
        )
    }
}
