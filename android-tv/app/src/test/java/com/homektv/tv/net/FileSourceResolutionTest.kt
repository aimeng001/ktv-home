package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSourceResolutionTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun timeoutIsRetryable() = runBlocking {
        val resolver = FileSourceResolver(FakeTransport(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
            ),
        ), json)

        val result = resolver.resolve(7L)

        assertTrue(result is FileSourceResolution.Retryable)
    }

    @Test
    fun http500IsRetryable() = runBlocking {
        val resolver = FileSourceResolver(FakeTransport(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_500", "server", status = 500),
            ),
        ), json)

        assertTrue(resolver.resolve(7L) is FileSourceResolution.Retryable)
    }

    @Test
    fun unauthorizedIsConfigurationFailureAndDoesNotRetry() = runBlocking {
        val resolver = FileSourceResolver(FakeTransport(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_401", "unauthorized", status = 401),
            ),
        ), json)

        assertTrue(resolver.resolve(7L) is FileSourceResolution.ConfigurationFailure)
    }

    @Test
    fun badRequestIsFatalProtocolFailure() = runBlocking {
        val resolver = FileSourceResolver(FakeTransport(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_400", "bad request", status = 400),
            ),
        ), json)

        assertTrue(resolver.resolve(7L) is FileSourceResolution.Fatal)
    }

    @Test
    fun invalidJsonIsFatalProtocolFailure() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(KtvApiResult.Success("not-json")),
            json,
        )

        val result = resolver.resolve(7L)

        assertTrue(result is FileSourceResolution.Fatal)
        assertEquals("SONG_DETAIL_DECODE", (result as FileSourceResolution.Fatal).error.code)
    }

    @Test
    fun song404IsAbsent() = runBlocking {
        val resolver = FileSourceResolver(FakeTransport(
            KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_404", "missing", status = 404),
            ),
        ), json)

        assertEquals(
            FileSourceResolution.Absent(AbsentReason.SONG_NOT_FOUND),
            resolver.resolve(7L),
        )
    }

    @Test
    fun emptyFilesIsAbsent() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(KtvApiResult.Success("{\"id\":7,\"files\":[]}")),
            json,
        )

        assertEquals(
            FileSourceResolution.Absent(AbsentReason.NO_VALID_FILE),
            resolver.resolve(7L),
        )
    }

    @Test
    fun malformedFileEntryIsFatalProtocolFailure() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(KtvApiResult.Success("{\"id\":7,\"files\":[{}]}")),
            json,
        )

        val result = resolver.resolve(7L)

        assertTrue(result is FileSourceResolution.Fatal)
        assertEquals("INVALID_FILE_SOURCE", (result as FileSourceResolution.Fatal).error.code)
    }

    @Test
    fun highestPriorityFileIsReady() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(
                KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1},{\"id\":12,\"priority\":4}]}"
                ),
            ),
            json,
        )

        val result = resolver.resolve(7L)

        assertEquals(12L, (result as FileSourceResolution.Ready).source.id)
    }

    /**
     * 服务端可能同时下发「未完成 FFprobe 的高优先级行」和「可播放的低优先级行」。
     * A file the server already knows is not probed must never win on priority alone.
     */
    @Test
    fun notReadyHighestPriorityFileIsSkippedForAReadyLowerPriorityFile() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(
                KtvApiResult.Success(
                    "{\"id\":7,\"files\":[" +
                        "{\"id\":21,\"priority\":900,\"ready\":false}," +
                        "{\"id\":22,\"priority\":12,\"ready\":true}]}",
                ),
            ),
            json,
        )

        val result = resolver.resolve(7L)

        assertEquals(22L, (result as FileSourceResolution.Ready).source.id)
    }

    @Test
    fun songWhoseOnlyFilesAreNotReadyIsReportedAsHavingNoValidFile() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(
                KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":31,\"priority\":900,\"ready\":false}]}",
                ),
            ),
            json,
        )

        assertEquals(
            FileSourceResolution.Absent(AbsentReason.NO_VALID_FILE),
            resolver.resolve(7L),
        )
    }

    /** 旧服务端不下发 ready 字段时不能被误判为不可播。 */
    @Test
    fun filesWithoutAReadyFlagStillResolveForOlderServers() = runBlocking {
        val resolver = FileSourceResolver(
            FakeTransport(
                KtvApiResult.Success("{\"id\":7,\"files\":[{\"id\":41,\"priority\":3}]}"),
            ),
            json,
        )

        val result = resolver.resolve(7L)

        assertEquals(41L, (result as FileSourceResolution.Ready).source.id)
    }

    private class FakeTransport(
        private val response: KtvApiResult<String>,
    ) : KtvTransport {
        override suspend fun get(path: String): KtvApiResult<String> = response
        override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> = response
        override suspend fun delete(path: String): KtvApiResult<String> = response
    }
}
