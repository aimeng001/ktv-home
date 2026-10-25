package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigProtocolTest {
    @Test
    fun websocketUrlUsesTlsForHttpsAndNeverContainsPlayerCredential() {
        assertEquals(
            "wss://192.168.1.10:8443/ws?client_type=tv&client_token=tv-1&protocol_version=2&platform=ANDROID_TV",
            buildTvWebSocketUrl("https://192.168.1.10:8443", "tv-1"),
        )
    }

    @Test
    fun explicitHttpsAddressIsPreservedDuringNormalization() {
        assertEquals("https://ktv.local:443", AppConfig.normalizeHost("https://ktv.local"))
        assertEquals("ktv.local:8080", AppConfig.normalizeHost("http://ktv.local"))
    }

    @Test
    fun playerCredentialIsOnlyReturnedForHttpsTransport() {
        assertEquals("secret/tv", playerCredentialForTransport("https://ktv.local:443", " secret/tv "))
        assertEquals(null, playerCredentialForTransport("ktv.local:8080", "secret/tv"))
    }

    @Test
    fun controllerWebsocketUsesWssForHttpsServer() {
        assertEquals(
            "wss://ktv.local:8443/ws?client_type=controller&client_token=user-1&protocol_version=2&platform=ANDROID_PHONE&device_mode=CONTROLLER",
            buildControllerWebSocketUrl("https://ktv.local:8443", "user-1"),
        )
    }

    @Test
    fun queryEncodingPreservesOnlyUnreservedUtf8Bytes() {
        assertEquals(
            "tv%20%E4%BD%A0%E5%A5%BD%26%3D%2F%2B._~-",
            encodeQueryComponent("tv 你好&=/+._~-"),
        )
    }

    @Test
    fun hostNormalizationHandlesEmptyPathAndDefaultPorts() {
        assertEquals(null, AppConfig.normalizeHost("  "))
        assertEquals(null, AppConfig.normalizeHost("https://"))
        assertEquals(null, AppConfig.normalizeHost("/"))
        assertEquals("example.local:8080", AppConfig.normalizeHost("http://example.local/path"))
        assertEquals("https://example.local:443", AppConfig.normalizeHost("HTTPS://example.local/path"))
        assertEquals("nas.local:9900", AppConfig.normalizeHost("nas.local:9900/path"))
    }

    @Test
    fun httpUrlBuilderTrimsPathSeparatorsAndUsesTheConfiguredTransport() {
        assertEquals(
            "https://ktv.local:8443/api/ready",
            buildServerHttpUrl("https://ktv.local:8443/", "/api/ready"),
        )
        assertEquals("http://ktv.local:8080/api/ready", buildServerHttpUrl("ktv.local:8080", "api/ready"))
    }

    @Test
    fun playerCredentialIsRejectedForBlankOrMissingValues() {
        assertEquals(null, playerCredentialForTransport("https://ktv.local:443", null))
        assertEquals(null, playerCredentialForTransport("https://ktv.local:443", "   "))
        assertEquals(null, playerCredentialForTransport("https://ktv.local:443", ""))
        assertEquals("secret", playerCredentialForTransport("https://ktv.local:443", " secret "))
        assertEquals(null, playerCredentialForTransport("", "secret"))
    }
}
