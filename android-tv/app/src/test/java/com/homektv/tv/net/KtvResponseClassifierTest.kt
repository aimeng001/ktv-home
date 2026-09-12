package com.homektv.tv.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvResponseClassifierTest {
    @Test
    fun businessEnvelopeWinsEvenWhenHttpStatusIs200() {
        val result = classifyKtvResponse(200, "{\"code\":\"TV_OFFLINE\",\"message\":\"offline\"}")
        assertTrue(result is KtvApiResult.Failure)
        assertEquals("TV_OFFLINE", (result as KtvApiResult.Failure).error.code)
    }

    @Test
    fun nonJsonHttpErrorIsMappedWithoutLeakingBody() {
        val result = classifyKtvResponse(503, "secret stack trace")
        assertTrue(result is KtvApiResult.Failure)
        val error = (result as KtvApiResult.Failure).error
        assertEquals(KtvApiErrorKind.HTTP, error.kind)
        assertEquals("HTTP_503", error.code)
        assertTrue(!error.message.contains("secret"))
    }

    @Test
    fun validJsonOrTextResponseRemainsSuccessful() {
        assertTrue(classifyKtvResponse(200, "[]") is KtvApiResult.Success)
        assertTrue(classifyKtvResponse(204, "") is KtvApiResult.Success)
    }

    @Test
    fun nonPrimitiveErrorFieldsDoNotThrowFromTheTransportCallback() {
        val result = classifyKtvResponse(200, "{\"code\":{},\"message\":[]}")
        assertTrue(result is KtvApiResult.Success)
    }

    @Test
    fun controlResponseWithMissingBodyFailsClosed() {
        assertTrue(!isSuccessfulKtvControlResponse(200, null))
        assertTrue(!isSuccessfulKtvControlResponse(200, "{\"code\":\"TV_OFFLINE\"}"))
        assertTrue(isSuccessfulKtvControlResponse(200, "{}"))
    }
}
