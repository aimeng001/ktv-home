package com.homektv.tv.net

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KtvTransportTest {
    @Test
    fun defaultControllerClientDisablesAutomaticRetryForWrites() {
        val client = KtvHttpTransport.defaultClient()
        try {
            assertFalse(client.retryOnConnectionFailure)
        } finally {
            client.closeResources()
        }
    }

    @Test
    fun unconfiguredServerHasAnExplicitFailureBeforeUrlConstruction() {
        assertEquals("SERVER_NOT_CONFIGURED", serverConfigurationFailure(null)?.error?.code)
        assertEquals("SERVER_NOT_CONFIGURED", serverConfigurationFailure("  ")?.error?.code)
        assertNull(serverConfigurationFailure("nas.local:8080"))
    }

    @Test
    fun url_normalizesPrefixedApiAndAbsoluteUrls() {
        val host = "192.168.1.5:8080"
        assertEquals(
            "http://192.168.1.5:8080/api/artists/avatar?key=zhou",
            KtvHttpTransport.resolveUrl(host, "/api/artists/avatar?key=zhou"),
        )
        assertEquals(
            "http://192.168.1.5:8080/api/songs?keyword=q",
            KtvHttpTransport.resolveUrl(host, "/songs?keyword=q"),
        )
        assertEquals(
            "http://192.168.1.5:8080/api/songs",
            KtvHttpTransport.resolveUrl(host, "songs"),
        )
        assertEquals(
            "https://p1.music.126.net/avatar.jpg",
            KtvHttpTransport.resolveUrl(host, "https://p1.music.126.net/avatar.jpg"),
        )
    }
}
