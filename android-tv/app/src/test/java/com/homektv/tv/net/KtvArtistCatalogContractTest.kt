package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvArtistCatalogContractTest {
    @Test
    fun artistPageDecodesGenderKindCountAndAvatar() = runBlocking {
        val transport = CatalogRecordingTransport(
            KtvApiResult.Success(
                """{"items":[{"artistKey":"zhoujielun","name":"周杰伦","initial":"Z","gender":"男歌手","songCount":250,"artistKind":"PERSON","avatarUrl":"/api/artists/avatar?key=zhoujielun"}],"total":1,"page":0,"size":30}""",
            ),
        )

        val result = SongApi(transport).browseArtistsPage(page = 0, size = 30, gender = "男歌手", initial = "Z")

        assertTrue(result is KtvApiResult.Success)
        val page = (result as KtvApiResult.Success).value
        assertEquals(1L, page.total)
        assertEquals("zhoujielun", page.items.single().artistKey)
        assertEquals("男歌手", page.items.single().gender)
        assertEquals(250, page.items.single().songCount)
        assertEquals("PERSON", page.items.single().artistKind)
        assertEquals("/api/artists/avatar?key=zhoujielun", page.items.single().avatarUrl)
    }

    @Test
    fun artistSongBrowseUsesStableArtistKeyInsteadOfDisplayNameOnly() = runBlocking {
        val transport = CatalogRecordingTransport(
            KtvApiResult.Success("""{"items":[{"id":7,"title":"晴天","artist":"周杰伦"}],"total":1,"page":1,"size":50}"""),
        )

        val result = SongApi(transport).browseSongPage(
            page = 1,
            size = 50,
            artist = "周杰伦",
            artistKey = "zhoujielun",
        )

        assertTrue(result is KtvApiResult.Success)
        assertEquals(7L, (result as KtvApiResult.Success).value.items.single().id)
        assertEquals(
            "/browse/songs/page?artist=%E5%91%A8%E6%9D%B0%E4%BC%A6&artistKey=zhoujielun&artistGender=&language=&tag=&sort=hot&page=1&size=50",
            transport.lastGet,
        )
    }

    @Test
    fun malformedCatalogJsonIsReportedAsDecodeFailure() = runBlocking {
        val result = SongApi(CatalogRecordingTransport(KtvApiResult.Success("not-json")))
            .browseArtistsPage()

        assertTrue(result is KtvApiResult.Failure)
        assertEquals(KtvApiErrorKind.DECODE, (result as KtvApiResult.Failure).error.kind)
        assertEquals("INVALID_RESPONSE", result.error.code)
    }
}

private class CatalogRecordingTransport(
    private val response: KtvApiResult<String>,
) : KtvTransport {
    var lastGet: String? = null

    override suspend fun get(path: String): KtvApiResult<String> {
        lastGet = path
        return response
    }

    override suspend fun post(path: String, body: kotlinx.serialization.json.JsonObject): KtvApiResult<String> =
        KtvApiResult.Success("{}")

    override suspend fun delete(path: String): KtvApiResult<String> = KtvApiResult.Success("{}")
}
