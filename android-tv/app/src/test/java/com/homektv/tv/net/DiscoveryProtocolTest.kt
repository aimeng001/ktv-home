package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryProtocolTest {
    @Test
    fun parsesValidDiscoveryResponseUsingPacketSourceAddress() {
        val payload = """{"service":"home-ktv","protocolVersion":1,"name":"客厅KTV","port":12345}"""
        assertEquals(
            DiscoveredServer("192.168.1.20:12345", "客厅KTV"),
            DiscoveryProtocol.parseResponse(payload, "192.168.1.20"),
        )
    }

    @Test
    fun rejectsWrongServiceVersionAndPort() {
        assertNull(DiscoveryProtocol.parseResponse("""{"service":"other","protocolVersion":1,"port":8080}""", "10.0.0.2"))
        assertNull(DiscoveryProtocol.parseResponse("""{"service":"home-ktv","protocolVersion":2,"port":8080}""", "10.0.0.2"))
        assertNull(DiscoveryProtocol.parseResponse("""{"service":"home-ktv","protocolVersion":1,"port":70000}""", "10.0.0.2"))
    }

    @Test
    fun rejectsMissingMalformedAndWronglyTypedRequiredFields() {
        listOf(
            "",
            "null",
            "[]",
            "{}",
            """{"service":"home-ktv","protocolVersion":"bad","port":8080}""",
            """{"service":"home-ktv","protocolVersion":1}""",
            """{"service":"home-ktv","protocolVersion":1,"port":"bad"}""",
            """{"service":{},"protocolVersion":1,"port":8080}""",
        ).forEach { payload ->
            assertNull("payload should be rejected: $payload", DiscoveryProtocol.parseResponse(payload, "10.0.0.2"))
        }
    }

    @Test
    fun acceptsBothPortBoundariesAndUsesTheSourceAddressForBlankNames() {
        listOf(1, 65_535).forEach { port ->
            val parsed = DiscoveryProtocol.parseResponse(
                """{"service":"home-ktv","protocolVersion":1,"name":"  ","port":$port}""",
                "192.168.1.20",
            )

            assertEquals(DiscoveredServer("192.168.1.20:$port", "192.168.1.20"), parsed)
        }
    }

    @Test
    fun bracketsIpv6PacketSourceAndDoesNotDoubleBracketAnAlreadyFormattedHost() {
        val payload = """{"service":"home-ktv","protocolVersion":1,"port":8080}"""

        assertEquals(
            DiscoveredServer("[2001:db8::20]:8080", "2001:db8::20"),
            DiscoveryProtocol.parseResponse(payload, "2001:db8::20"),
        )
        assertEquals(
            DiscoveredServer("[2001:db8::20]:8080", "[2001:db8::20]"),
            DiscoveryProtocol.parseResponse(payload, "[2001:db8::20]"),
        )
    }

    @Test
    fun rejectsInvalidOptionalInstanceIdRepresentationsAndNormalizesValidIds() {
        listOf("", " ", "not-a-uuid", "null", "42").forEach { value ->
            val payload = """{"service":"home-ktv","protocolVersion":1,"port":8080,"instanceId":"$value"}"""
            assertNull("invalid instance ID should be rejected: $value", DiscoveryProtocol.parseResponse(payload, "10.0.0.2"))
        }

        assertNull(
            DiscoveryProtocol.parseResponse(
                """{"service":"home-ktv","protocolVersion":1,"port":8080,"instanceId":null}""",
                "10.0.0.2",
            ),
        )
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            DiscoveryProtocol.normalizeInstanceId(" 550E8400-E29B-41D4-A716-446655440000 "),
        )
        assertNull(DiscoveryProtocol.normalizeInstanceId(null))
        assertNull(DiscoveryProtocol.normalizeInstanceId(" \t "))
    }

    @Test
    fun parsesOptionalInstanceIdWithoutChangingV1Protocol() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        val payload = """{"service":"home-ktv","protocolVersion":1,"name":"客厅KTV","port":12345,"instanceId":"$id"}"""

        assertEquals(
            DiscoveredServer("192.168.1.20:12345", "客厅KTV", id),
            DiscoveryProtocol.parseResponse(payload, "192.168.1.20"),
        )
    }

    @Test
    fun rejectsMalformedInstanceIdInsteadOfTreatingItAsLegacyServer() {
        val payload = """{"service":"home-ktv","protocolVersion":1,"port":12345,"instanceId":"not-an-id"}"""

        assertNull(DiscoveryProtocol.parseResponse(payload, "192.168.1.20"))
    }
}
