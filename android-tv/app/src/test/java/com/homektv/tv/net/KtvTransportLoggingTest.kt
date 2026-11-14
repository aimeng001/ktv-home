package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvTransportLoggingTest {

    @Test
    fun safeHttpLogUrlSanitizesSensitiveQueryParameters() {
        val raw = "http://192.168.1.10:8080/api/user/register?token=secret123&user=admin"
        val sanitized = safeHttpLogUrl(raw)

        assertEquals("http://192.168.1.10:8080/api/user/register?<sanitized>", sanitized)
        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("admin"))
    }

    @Test
    fun safeHttpLogUrlPreservesPathWhenNoQueryParams() {
        val raw = "http://192.168.1.10:8080/api/ranking"
        val sanitized = safeHttpLogUrl(raw)

        assertEquals("http://192.168.1.10:8080/api/ranking", sanitized)
    }
}
