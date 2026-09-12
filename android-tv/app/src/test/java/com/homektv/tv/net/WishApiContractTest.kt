package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WishApiContractTest {
    @Test
    fun missingSongWishUsesThePublicClientTokenField() = runBlocking {
        val transport = WishRecordingTransport()

        WishApi(transport, "user-1").add("  海阔天空  ")

        assertEquals("/wishes", transport.path)
        assertEquals("海阔天空", transport.body?.get("keyword")?.jsonPrimitive?.content)
        assertEquals("user-1", transport.body?.get("client_token")?.jsonPrimitive?.content)
    }
}

private class WishRecordingTransport : KtvTransport {
    var path: String? = null
    var body: JsonObject? = null

    override suspend fun get(path: String): KtvApiResult<String> = KtvApiResult.Success("{}")

    override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> {
        this.path = path
        this.body = body
        return KtvApiResult.Success("{\"status\":\"ok\"}")
    }

    override suspend fun delete(path: String): KtvApiResult<String> = KtvApiResult.Success("{}")
}
