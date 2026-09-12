package com.homektv.tv.net

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlRequestBuilderTest {
    @Test
    fun tvRemoteRequestsCarryTheControllerUserToken() {
        val body = buildControlRequest("next", "{}", "user-1")
        assertEquals("next", body?.get("action")?.jsonPrimitive?.content)
        assertEquals("user-1", body?.get("client_token")?.jsonPrimitive?.content)
    }

    @Test
    fun malformedParamsAreRejectedInsteadOfInterpolatedIntoJson() {
        assertNull(buildControlRequest("next", "{bad", "user-1"))
    }

    @Test
    fun vocalTrackSwapUsesTheSharedControlAction() {
        val body = buildControlRequest("swap_vocal_tracks", "{}", "user-1")
        assertEquals("swap_vocal_tracks", body?.get("action")?.jsonPrimitive?.content)
    }
}
