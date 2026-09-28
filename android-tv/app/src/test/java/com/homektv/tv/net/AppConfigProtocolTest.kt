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
}
