package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryApiTest {
    @Test
    fun statusPassesTransportFailuresThroughUnchanged() = runBlocking {
        val failure = KtvApiResult.Failure(
            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "无法连接点歌服务"),
        )

        val result = LibraryApi(StubTransport(failure)).status()

        assertSame(failure, result)
    }

    @Test
    fun statusDecodesKnownFieldsAndIgnoresFieldsFromNewerServers() = runBlocking {
        val result = LibraryApi(
            StubTransport(KtvApiResult.Success("""
                {"totalSongs":12,"libraryMode":"EXTERNAL_READ_ONLY","futureField":"safe to ignore"}
            """.trimIndent())),
        ).status()

        assertEquals(
            KtvApiResult.Success(LibraryStatus(totalSongs = 12, libraryMode = "EXTERNAL_READ_ONLY")),
            result,
        )
    }

    @Test
    fun statusConvertsMalformedResponsesToSafeDecodeFailures() = runBlocking {
        val result = LibraryApi(StubTransport(KtvApiResult.Success("{\"totalSongs\":\"many\"}"))).status()

        assertEquals(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端数据格式异常"),
            ),
            result,
        )
    }

    private class StubTransport(private val response: KtvApiResult<String>) : KtvTransport {
        override suspend fun get(path: String): KtvApiResult<String> {
            assertEquals("/library/status", path)
            return response
        }

        override suspend fun post(path: String, body: kotlinx.serialization.json.JsonObject) =
            error("LibraryApi.status does not use POST")

        override suspend fun delete(path: String) = error("LibraryApi.status does not use DELETE")
    }
}
