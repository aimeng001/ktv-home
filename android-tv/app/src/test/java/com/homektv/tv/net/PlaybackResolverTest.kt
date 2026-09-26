package com.homektv.tv.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val apiBase = "http://192.168.1.10:8080/api"

    @Test
    fun fileSourceAbsentProducesAbsentResolution() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.HTTP, "HTTP_404", "missing", status = 404),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertEquals(PlaybackResolution.Absent(AbsentReason.SONG_NOT_FOUND), result)
    }

    @Test
    fun fileSourceFatalProducesFatalResolution() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success("{\"id\":7,\"files\":[{}]}"),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Fatal)
        assertEquals("INVALID_FILE_SOURCE", (result as PlaybackResolution.Fatal).error.code)
    }

    @Test
    fun fileSourceConfigFailureProducesConfigFailureResolution() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.HTTP, "HTTP_401", "unauthorized", status = 401),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.ConfigurationFailure)
        assertEquals(401, (result as PlaybackResolution.ConfigurationFailure).error.status)
    }

    @Test
    fun fileSourceRetryableProducesRetryableResolution() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Retryable)
    }

    @Test
    fun readyResolutionWithRelativeUrlNormalizesToAbsoluteUrl() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"TRANSCODE\",\"status\":\"READY\",\"sourceFileId\":11,\"variantId\":99,\"streamUrl\":\"/api/playback/stream/99\",\"audioTracks\":1}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Ready)
        val ready = result as PlaybackResolution.Ready
        assertEquals("http://192.168.1.10:8080/api/playback/stream/99", ready.descriptor.streamUrl)
        assertEquals(99L, ready.descriptor.variantId)
    }

    @Test
    fun readyResolutionWithAbsoluteUrlPreservesUrl() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"NATIVE\",\"status\":\"READY\",\"sourceFileId\":11,\"streamUrl\":\"http://cdn.local:9000/stream/11\",\"audioTracks\":2}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Ready)
        val ready = result as PlaybackResolution.Ready
        assertEquals("http://cdn.local:9000/stream/11", ready.descriptor.streamUrl)
    }

    @Test
    fun readyResolutionWithMissingUrlProducesFatalUrlMissing() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"TRANSCODE\",\"status\":\"READY\",\"sourceFileId\":11,\"variantId\":99,\"streamUrl\":\"\"}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Fatal)
        assertEquals("PLAYBACK_URL_MISSING", (result as PlaybackResolution.Fatal).error.code)
    }

    @Test
    fun preparingResolutionReturnsPreparingDescriptor() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"TRANSCODE\",\"status\":\"PREPARING\",\"sourceFileId\":11,\"variantId\":99}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Preparing)
        val preparing = result as PlaybackResolution.Preparing
        assertEquals("PREPARING", preparing.descriptor.status)
        assertEquals(99L, preparing.descriptor.variantId)
    }

    @Test
    fun failedResolutionReturnsFailedDescriptorWithMappedError() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"TRANSCODE\",\"status\":\"FAILED\",\"sourceFileId\":11,\"errorCode\":\"TRANSCODE_EXECUTION_FAILED\",\"errorMessage\":\"FFmpeg exited with error\"}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Failed)
        val failed = result as PlaybackResolution.Failed
        assertEquals("FAILED", failed.descriptor.status)
        assertEquals("TRANSCODE_EXECUTION_FAILED", failed.error.code)
        assertEquals("FFmpeg exited with error", failed.error.message)
    }

    @Test
    fun unknownStatusReturnsFatalInvalidStatus() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success(
                    "{\"kind\":\"NATIVE\",\"status\":\"UNEXPECTED_STATUS\",\"sourceFileId\":11}",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Fatal)
        assertEquals("PLAYBACK_STATUS_INVALID", (result as PlaybackResolution.Fatal).error.code)
    }

    @Test
    fun malformedJsonReturnsFatalDecodeError() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Success("corrupted {json"),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Fatal)
        assertEquals("PLAYBACK_DECODE", (result as PlaybackResolution.Fatal).error.code)
    }

    @Test
    fun legacyServerHttp404WithHttp404CodeFallsBackToNativeReady() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true,\"audioTracks\":2,\"vocalTrackIndex\":1}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.HTTP, "HTTP_404", "Endpoint not found", status = 404),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.Ready)
        val ready = result as PlaybackResolution.Ready
        assertEquals("NATIVE", ready.descriptor.kind)
        assertEquals("READY", ready.descriptor.status)
        assertEquals("http://192.168.1.10:8080/api/stream/11", ready.descriptor.streamUrl)
        assertEquals(2, ready.descriptor.audioTracks)
    }

    @Test
    fun explicitFileNotFoundProducesAbsentResolution() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.BUSINESS, "FILE_NOT_FOUND", "NAS file removed", status = 404),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertEquals(PlaybackResolution.Absent(AbsentReason.NO_VALID_FILE), result)
    }

    @Test
    fun unauthorizedOrForbiddenProducesConfigurationFailure() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.HTTP, "HTTP_401", "Unauthorized", status = 401),
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L)

        assertTrue(result is PlaybackResolution.ConfigurationFailure)
        assertEquals(401, (result as PlaybackResolution.ConfigurationFailure).error.status)
    }

    @Test
    fun networkOr5xxOr429Or408ProducesRetryable() = runBlocking {
        val transport503 = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.HTTP, "HTTP_503", "Service Unavailable", status = 503),
                ),
            ),
        )
        val resolver503 = PlaybackResolver(transport503, json, { apiBase })
        assertTrue(resolver503.resolve(7L) is PlaybackResolution.Retryable)

        val transportNetwork = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                ),
                "/playback/resolve/11" to KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "timeout"),
                ),
            ),
        )
        val resolverNetwork = PlaybackResolver(transportNetwork, json, { apiBase })
        assertTrue(resolverNetwork.resolve(7L) is PlaybackResolution.Retryable)
    }

    @Test
    fun forceTranscodeQueryParameterAppendedWhenRequested() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val transport = object : KtvTransport {
            override suspend fun get(path: String): KtvApiResult<String> {
                requestedPaths.add(path)
                return when (path) {
                    "/songs/7" -> KtvApiResult.Success(
                        "{\"id\":7,\"files\":[{\"id\":11,\"priority\":1,\"ready\":true}]}",
                    )
                    "/playback/resolve/11?forceTranscode=true" -> KtvApiResult.Success(
                        "{\"kind\":\"TRANSCODE\",\"status\":\"PREPARING\",\"sourceFileId\":11,\"variantId\":99}",
                    )
                    else -> KtvApiResult.Failure(
                        KtvApiError(KtvApiErrorKind.HTTP, "HTTP_404", "Not found", status = 404),
                    )
                }
            }
            override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> = error("unused")
            override suspend fun delete(path: String): KtvApiResult<String> = error("unused")
        }
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L, forceTranscode = true)

        assertTrue(result is PlaybackResolution.Preparing)
        assertTrue(requestedPaths.contains("/playback/resolve/11?forceTranscode=true"))
    }

    @Test
    fun liveTranscodeDescriptorWithoutVariantIsAcceptedAsReady() = runBlocking {
        val transport = RouteTransport(
            routes = mapOf(
                "/songs/7" to KtvApiResult.Success(
                    """{"id":7,"files":[{"id":11,"priority":1,"ready":true,"audioTracks":2}]}""",
                ),
                "/playback/resolve/11?forceTranscode=true" to KtvApiResult.Success(
                    """{"kind":"LIVE_TRANSCODE","status":"READY","sourceFileId":11,"variantId":null,"streamUrl":"/api/stream/11?transcode=true","audioTracks":2,"audioLayout":{"layout":"DUAL_TRACK","accompanimentTrackIndex":1}}""",
                ),
            ),
        )
        val resolver = PlaybackResolver(transport, json, { apiBase })

        val result = resolver.resolve(7L, forceTranscode = true)

        assertTrue(result is PlaybackResolution.Ready)
        val ready = result as PlaybackResolution.Ready
        assertEquals("LIVE_TRANSCODE", ready.descriptor.kind)
        assertEquals(11L, ready.descriptor.sourceFileId)
        assertEquals(null, ready.descriptor.variantId)
        assertEquals("http://192.168.1.10:8080/api/stream/11?transcode=true", ready.descriptor.streamUrl)
        assertEquals("DUAL_TRACK", ready.descriptor.audioLayout.layout)
        assertEquals(1, ready.descriptor.audioLayout.accompanimentTrackIndex)
    }

    private class RouteTransport(
        private val routes: Map<String, KtvApiResult<String>>,
    ) : KtvTransport {
        override suspend fun get(path: String): KtvApiResult<String> =
            routes[path] ?: KtvApiResult.Failure(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_404", "Unhandled route: $path", status = 404),
            )
        override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> = error("unused")
        override suspend fun delete(path: String): KtvApiResult<String> = error("unused")
    }
}
