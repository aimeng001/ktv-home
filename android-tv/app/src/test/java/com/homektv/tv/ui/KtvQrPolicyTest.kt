package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvQrPolicyTest {

    @Test
    fun formatPortalUrl_prependsHttpWhenMissing() {
        assertEquals("http://192.168.1.100:8080", KtvQrPolicy.formatPortalUrl("192.168.1.100:8080"))
        assertEquals("http://ktv.local:8080", KtvQrPolicy.formatPortalUrl("ktv.local:8080"))
    }

    @Test
    fun formatPortalUrl_preservesExistingScheme() {
        assertEquals("http://192.168.1.100:8080", KtvQrPolicy.formatPortalUrl("http://192.168.1.100:8080"))
        assertEquals("https://ktv.example.com", KtvQrPolicy.formatPortalUrl("https://ktv.example.com"))
    }

    @Test
    fun formatPortalUrl_handlesNullOrBlank() {
        assertEquals("", KtvQrPolicy.formatPortalUrl(null))
        assertEquals("", KtvQrPolicy.formatPortalUrl("   "))
    }
}
