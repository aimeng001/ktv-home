package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigProtocolTest {
    @Test
    fun websocketUrlIncludesConfiguredPlayerCredentialWithoutLoggingIt() {
        assertEquals(
            "ws://192.168.1.10:8080/ws?client_type=tv&client_token=tv-1&protocol_version=2&platform=ANDROID_TV&player_credential=secret%2Ftv",
            buildTvWebSocketUrl("192.168.1.10:8080", "tv-1", "secret/tv"),
        )
    }
}
