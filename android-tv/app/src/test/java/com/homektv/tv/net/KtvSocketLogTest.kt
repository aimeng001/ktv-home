package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KtvSocketLogTest {
    @Test
    fun connectionLogTargetContainsNoClientTokenOrQueryString() {
        val target = safeWebSocketLogTarget("192.168.1.10:54001")

        assertEquals("ws://192.168.1.10:54001/ws", target)
        assertFalse(target.contains("client_token"))
        assertFalse(target.contains("tv-secret"))
    }
}
