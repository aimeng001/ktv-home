package com.homektv.tv.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface KtvTransport {
    suspend fun get(path: String): KtvApiResult<String>

    /** Bounded binary reads are used only for server-provided cover artwork. */
    suspend fun getBytes(path: String): KtvApiResult<ByteArray> = KtvApiResult.Failure(
        KtvApiError(KtvApiErrorKind.NETWORK, "BINARY_UNSUPPORTED", "当前传输不支持图片读取"),
    )

    suspend fun post(path: String, body: JsonObject): KtvApiResult<String>

    suspend fun delete(path: String): KtvApiResult<String>
}

/**
 * Shared, bounded HTTP transport for controller-domain APIs. Each coroutine
 * owns a cancellable OkHttp call, so a cancelled search cannot keep consuming
 * a connection after the UI has moved to a newer query.
 */
class KtvHttpTransport(
    private val config: AppConfig,
    private val http: OkHttpClient = defaultClient(),
) : KtvTransport, Closeable {

    override suspend fun get(path: String): KtvApiResult<String> {
        val host = config.serverHost
        serverConfigurationFailure(host)?.let { return it }
        return execute(Request.Builder().url(url(host!!, path)).get().build())
    }

    override suspend fun getBytes(path: String): KtvApiResult<ByteArray> {
        val host = config.serverHost
        serverConfigurationFailure(host)?.let { return it }
        return executeBytes(Request.Builder().url(url(host!!, path)).get().build())
    }

    override suspend fun post(path: String, body: JsonObject): KtvApiResult<String> {
        val host = config.serverHost
        serverConfigurationFailure(host)?.let { return it }
        return execute(
            Request.Builder()
                .url(url(host!!, path))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    override suspend fun delete(path: String): KtvApiResult<String> {
        val host = config.serverHost
        serverConfigurationFailure(host)?.let { return it }
        return execute(Request.Builder().url(url(host!!, path)).delete().build())
    }

    override fun close() {
        http.closeResources()
    }

    private fun url(host: String, path: String): String = resolveUrl(host, path)

    private suspend fun execute(request: Request): KtvApiResult<String> =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            KtvApiResult.Failure(
                                KtvApiError(
                                    kind = KtvApiErrorKind.NETWORK,
                                    code = "NETWORK_ERROR",
                                    message = "无法连接点歌服务",
                                ),
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { current ->
                        val body = ResponseBodyReader.readText(current.body)
                        if (body == null) {
                            KtvApiResult.Failure(
                                KtvApiError(
                                    kind = KtvApiErrorKind.PAYLOAD_TOO_LARGE,
                                    code = "PAYLOAD_TOO_LARGE",
                                    message = "服务端响应过大",
                                    status = current.code,
                                ),
                            )
                        } else {
                            classifyKtvResponse(current.code, body)
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private suspend fun executeBytes(request: Request): KtvApiResult<ByteArray> =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        KtvApiResult.Failure(
                            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "无法连接点歌服务"),
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { current ->
                        val bytes = if (current.isSuccessful) {
                            ResponseBodyReader.readBytes(current.body)
                        } else null
                        when {
                            bytes != null -> KtvApiResult.Success(bytes)
                            current.code in 200..299 -> KtvApiResult.Failure(
                                KtvApiError(
                                    KtvApiErrorKind.PAYLOAD_TOO_LARGE,
                                    "PAYLOAD_TOO_LARGE",
                                    "服务端图片过大",
                                    status = current.code,
                                ),
                            )
                            else -> KtvApiResult.Failure(
                                KtvApiError(
                                    KtvApiErrorKind.HTTP,
                                    "HTTP_${current.code}",
                                    "服务端暂时不可用",
                                    status = current.code,
                                ),
                            )
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // A timed-out write has an unknown server outcome. The caller must
            // decide whether to retry after refreshing authoritative state.
            .retryOnConnectionFailure(false)
            .build()

        internal fun resolveUrl(host: String, path: String): String {
            val trimmed = path.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return trimmed
            }
            val cleanPath = trimmed.removePrefix("/")
            return if (cleanPath.startsWith("api/", ignoreCase = true)) {
                "http://$host/$cleanPath"
            } else {
                "http://$host/api/$cleanPath"
            }
        }
    }
}

/** Public controller entry points must remain safe before SetupActivity runs. */
internal fun serverConfigurationFailure(serverHost: String?): KtvApiResult.Failure? =
    if (serverHost.isNullOrBlank()) {
        KtvApiResult.Failure(
            KtvApiError(
                kind = KtvApiErrorKind.NETWORK,
                code = "SERVER_NOT_CONFIGURED",
                message = "请先配置点歌服务",
            ),
        )
    } else null
