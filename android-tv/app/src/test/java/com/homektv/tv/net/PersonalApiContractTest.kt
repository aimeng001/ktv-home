package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalApiContractTest {
    @Test
    fun favoriteOperationsKeepClientTokenNamingFromServerContract() = runBlocking {
        val transport = PersonalRecordingTransport()
        val api = FavoriteApi(transport, "user-1")

        api.add(42)

        assertEquals("/favorites/42", transport.lastPostPath)
        assertEquals("user-1", transport.lastPostBody?.get("clientToken")?.jsonPrimitive?.content)
    }

    @Test
    fun recentHistoryUsesServerQueryNameAndCanFilterMine() = runBlocking {
        val transport = PersonalRecordingTransport()
        RecentHistoryApi(transport, "user/1").list(mine = true)

        assertEquals("/history/recent?clientToken=user%2F1&mine=true", transport.lastGet)
    }

    @Test
    fun playlistOrderUsesSharedPlaylistEndpoint() = runBlocking {
        val transport = PersonalRecordingTransport()
        PlaylistApi(transport, "user-1").orderAll(8)

        assertEquals("/playlists/8/order", transport.lastPostPath)
        assertEquals("user-1", transport.lastPostBody?.get("clientToken")?.jsonPrimitive?.content)
        assertTrue(transport.lastPostBody?.containsKey("client_token") != true)
    }

    @Test
    fun playlistCoverUsesTheBoundedBinaryEndpoint() = runBlocking {
        val transport = PersonalRecordingTransport()
        PlaylistApi(transport, "user-1").cover(8)

        assertEquals("/playlists/8/cover", transport.lastBytesPath)
    }
}

private class PersonalRecordingTransport : KtvTransport {
    var lastGet: String? = null
    var lastPostPath: String? = null
    var lastPostBody: JsonObject? = null
    var lastBytesPath: String? = null

    override suspend fun get(path: String): KtvApiResult<String> {
        lastGet = path
        return KtvApiResult.Success("[]")
    }

    override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> {
        lastPostPath = path
        lastPostBody = body
        return KtvApiResult.Success("{}")
    }

    override suspend fun getBytes(path: String): KtvApiResult<ByteArray> {
        lastBytesPath = path
        return KtvApiResult.Success(ByteArray(0))
    }

    override suspend fun delete(path: String): KtvApiResult<String> = KtvApiResult.Success("{}")
}
