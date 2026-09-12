package com.homektv.tv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdentityTest {
    @Test
    fun playerAndUserTokensAreDifferentRoles() {
        val identity = SessionIdentity(
            serverHost = "192.168.1.10:8080",
            playerToken = "tv-abc",
            userToken = "user-xyz",
            nickname = "客厅",
        )

        assertNotEquals(identity.playerToken, identity.userToken)
        assertEquals("客厅", identity.nickname)
    }

    @Test
    fun serverKeyIsStableAndDoesNotContainSecrets() {
        val identity = SessionIdentity(
            serverHost = "192.168.1.10:8080",
            playerToken = "tv-secret",
            userToken = "user-secret",
        )

        assertEquals("192.168.1.10:8080", identity.serverKey)
        assertTrue(!identity.serverKey.contains("secret"))
    }
}
