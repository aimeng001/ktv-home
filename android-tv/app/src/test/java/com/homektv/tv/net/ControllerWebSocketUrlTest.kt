package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ControllerWebSocketUrlTest {
    @Test
    fun usesControllerTypeAndExplicitAndroidPlatformAndMode() {
        assertEquals(
            "ws://192.168.1.10:8080/ws?client_type=controller&client_token=user-1&protocol_version=2&platform=ANDROID_PHONE&device_mode=CONTROLLER",
            buildControllerWebSocketUrl("192.168.1.10:8080", "user-1"),
        )
    }

    @Test
    fun encodesUserTokenAndDoesNotAcceptPlayerCredential() {
        val url = buildControllerWebSocketUrl("nas.local:8080", "用户/1?x")
        assertEquals(
            "ws://nas.local:8080/ws?client_type=controller&client_token=%E7%94%A8%E6%88%B7%2F1%3Fx&protocol_version=2&platform=ANDROID_PHONE&device_mode=CONTROLLER",
            url,
        )
        assertFalse(url.contains("player_credential"))
    }
}
