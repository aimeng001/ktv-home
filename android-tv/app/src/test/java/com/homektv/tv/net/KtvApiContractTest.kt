package com.homektv.tv.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvApiContractTest {
    @Test
    fun songSearchUsesBoundedPagedEndpointAndEncodedKeyword() = runBlocking {
        val transport = RecordingTransport(
            getResponse = KtvApiResult.Success("[]"),
        )
        val result = SongApi(transport).search("周杰伦/晴天", page = 2)

        assertTrue(result is KtvApiResult.Success)
        assertEquals(
            "/songs?keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6%2F%E6%99%B4%E5%A4%A9&type=&page=2",
            transport.lastGet,
        )
    }

    @Test
    fun controlRequestUsesUserTokenAndSnakeCaseParams() = runBlocking {
        val transport = RecordingTransport(
            postResponse = KtvApiResult.Success("{\"state\":\"idle\"}"),
        )
        QueueApi(transport, userToken = "user-1").control(
            action = "order",
            params = mapOf("song_id" to 42, "force" to false),
        )

        val body = transport.lastPostBody
        requireNotNull(body)
        assertEquals("order", body["action"]?.jsonPrimitive?.content)
        assertEquals("user-1", body["client_token"]?.jsonPrimitive?.content)
        assertEquals("42", body["params"]?.let { it as JsonObject }["song_id"]?.jsonPrimitive?.content)
        assertEquals("false", body["params"]?.let { it as JsonObject }["force"]?.jsonPrimitive?.content)
    }

    @Test
    fun artistBrowseKeepsGenderInitialAndPageBoundaries() = runBlocking {
        val transport = RecordingTransport(
            getResponse = KtvApiResult.Success("{\"items\":[],\"total\":0,\"page\":2,\"size\":30}"),
        )

        val result = SongApi(transport).browseArtistsPage(
            page = 2,
            size = 30,
            gender = "女歌手",
            initial = "Z",
        )

        assertTrue(result is KtvApiResult.Success)
        assertEquals(
            "/browse/artists/page?gender=%E5%A5%B3%E6%AD%8C%E6%89%8B&initial=Z&page=2&size=30",
            transport.lastGet,
        )
    }

    @Test
    fun songDetailKeepsLanguageTagsAndPlaybackMetadata() = runBlocking {
        val transport = RecordingTransport(
            getResponse = KtvApiResult.Success(
                """{"id":7,"title":"晴天","artist":"周杰伦","language":"国语","tags":["流行"],"mediaType":"KTV_VIDEO","hasVocalTrack":true,"durationMs":240000,"lyricType":"line","coverUrl":"/api/cover/7","lyricUrl":"/api/lyric/7","playCount":12,"files":[]}""",
            ),
        )

        val result = SongApi(transport).songDetail(7)

        assertTrue(result is KtvApiResult.Success)
        val detail = (result as KtvApiResult.Success).value
        assertEquals("国语", detail.language)
        assertEquals(listOf("流行"), detail.tags)
        assertEquals(12, detail.playCount)
        assertEquals("/songs/7", transport.lastGet)
    }
}

private class RecordingTransport(
    private val getResponse: KtvApiResult<String> = KtvApiResult.Success("{}"),
    private val postResponse: KtvApiResult<String> = KtvApiResult.Success("{}"),
) : KtvTransport {
    var lastGet: String? = null
    var lastPostBody: JsonObject? = null

    override suspend fun get(path: String): KtvApiResult<String> {
        lastGet = path
        return getResponse
    }

    override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> {
        lastPostBody = body
        return postResponse
    }

    override suspend fun delete(path: String): KtvApiResult<String> = KtvApiResult.Success("{}")
}
