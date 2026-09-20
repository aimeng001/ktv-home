package com.homektv.tv.net

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TV 端 REST 客户端（P1.28）。
 *
 * now_playing 快照只携带 song.id，而拉流接口 GET /api/stream/{file_id} 需要
 * file_id（= song_files.id）。播放前先拉 GET /api/songs/{id} 详情，从 files
 * 里取 priority 最高的文件源，得到 fileId（顺带拿 vocalTrackIndex 供 P1.29 切轨）。
 *
 * The TV REST client resolves the highest-priority file source before playback
 * and also provides the vocal track index used by P1.29 track switching.
 */
class MediaApi(private val config: AppConfig) : Closeable {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val detailTransport = KtvHttpTransport(config)
    private val fileSourceResolver = FileSourceResolver(detailTransport, json)
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            http.closeResources()
            detailTransport.close()
        }
    }

    /** Resolves a file source while preserving transport failures as retryable state. */
    internal suspend fun resolveFileSource(songId: Long): FileSourceResolution =
        fileSourceResolver.resolve(songId)

    /** Resolves the authoritative native/sidecar playback descriptor. */
    internal suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean = false): PlaybackResolution {
        return when (val sourceResult = resolveFileSource(songId)) {
            is FileSourceResolution.Ready -> {
                val suffix = if (forceTranscode) "?forceTranscode=true" else ""
                when (val response = detailTransport.get("/playback/resolve/${sourceResult.source.id}$suffix")) {
                    is KtvApiResult.Success -> decodePlayback(sourceResult.source, response.value)
                    is KtvApiResult.Failure -> classifyPlaybackFailure(sourceResult.source, response.error)
                }
            }
            is FileSourceResolution.Absent -> PlaybackResolution.Absent(sourceResult.reason)
            is FileSourceResolution.Fatal -> PlaybackResolution.Fatal(sourceResult.error)
            is FileSourceResolution.ConfigurationFailure -> PlaybackResolution.ConfigurationFailure(sourceResult.error)
            is FileSourceResolution.Retryable -> PlaybackResolution.Retryable(sourceResult.error)
        }
    }

    private fun decodePlayback(source: FileSource, body: String): PlaybackResolution =
        runCatching { json.decodeFromString(PlaybackDescriptor.serializer(), body) }.fold(
            onSuccess = { descriptor ->
                val normalized = descriptor.copy(streamUrl = absolutePlaybackUrl(descriptor.streamUrl))
                when (normalized.status.uppercase()) {
                    "READY" -> if (normalized.streamUrl.isNullOrBlank()) {
                        PlaybackResolution.Fatal(KtvApiError(KtvApiErrorKind.DECODE, "PLAYBACK_URL_MISSING", "播放地址缺失"))
                    } else PlaybackResolution.Ready(source, normalized)
                    "PREPARING" -> PlaybackResolution.Preparing(source, normalized)
                    "FAILED" -> PlaybackResolution.Failed(
                        source,
                        normalized,
                        KtvApiError(
                            KtvApiErrorKind.DECODE,
                            normalized.errorCode ?: "PLAYBACK_PREPARE_FAILED",
                            normalized.errorMessage ?: "MV 准备失败",
                        ),
                    )
                    else -> PlaybackResolution.Fatal(KtvApiError(KtvApiErrorKind.DECODE, "PLAYBACK_STATUS_INVALID", "播放状态协议错误"))
                }
            },
            onFailure = {
                PlaybackResolution.Fatal(KtvApiError(KtvApiErrorKind.DECODE, "PLAYBACK_DECODE", "播放解析协议错误"))
            },
        )

    private fun classifyPlaybackFailure(source: FileSource, error: KtvApiError): PlaybackResolution = when {
        // Older servers do not expose the resolver endpoint. Keep native stream
        // compatibility; an upgraded server returns FILE_NOT_FOUND explicitly.
        error.kind == KtvApiErrorKind.HTTP && error.status == 404 && error.code == "HTTP_404" ->
            PlaybackResolution.Ready(
                source,
                PlaybackDescriptor(
                    kind = "NATIVE",
                    status = "READY",
                    sourceFileId = source.id,
                    streamUrl = streamUrl(source.id),
                    audioTracks = source.audioTracks,
                    vocalTrackIndex = source.audioLayout.accompanimentTrackIndex ?: source.vocalTrackIndex,
                    audioLayout = source.audioLayout,
                ),
            )
        error.code == "FILE_NOT_FOUND" || error.code == "NO_VALID_FILE" ->
            PlaybackResolution.Absent(AbsentReason.NO_VALID_FILE)
        error.kind == KtvApiErrorKind.HTTP && error.status == 404 -> PlaybackResolution.Absent(AbsentReason.NO_VALID_FILE)
        error.kind == KtvApiErrorKind.HTTP && error.status in setOf(401, 403) -> PlaybackResolution.ConfigurationFailure(error)
        error.kind == KtvApiErrorKind.NETWORK ||
            (error.kind == KtvApiErrorKind.HTTP && (error.status == 408 || error.status == 425 || error.status == 429 || (error.status ?: 0) >= 500)) ->
            PlaybackResolution.Retryable(error)
        else -> PlaybackResolution.Fatal(error)
    }

    private fun absolutePlaybackUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = config.apiBase().removeSuffix("/api")
        return base + "/" + path.removePrefix("/")
    }
    suspend fun bestFileSource(songId: Long): FileSource? =
        when (val res = resolveFileSource(songId)) {
            is FileSourceResolution.Ready -> res.source
            else -> null
        }

    suspend fun fetchLyric(songId: Long): String? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("${config.apiBase()}/lyric/$songId").build()).use { resp ->
                if (resp.isSuccessful) ResponseBodyReader.readText(resp.body) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    suspend fun fetchCover(songId: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("${config.apiBase()}/cover/$songId").build()).use { resp ->
                if (resp.isSuccessful) ResponseBodyReader.readBytes(resp.body) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    suspend fun control(action: String, params: String = "{}"): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = buildControlRequest(action, params, config.userToken) ?: return@withContext false
            val requestBody = body.toString().toRequestBody("application/json".toMediaType())
            execute(Request.Builder().url("${config.apiBase()}/control").post(requestBody).build()).use { response ->
                if (!response.isSuccessful) {
                    false
                } else {
                    // The server can return a 200 response containing a
                    // business error envelope (for example TV_OFFLINE).
                    isSuccessfulKtvControlResponse(
                        response.code,
                        ResponseBodyReader.readText(response.body),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
    }

    suspend fun fetchRecommendations(): List<SongDto> = withContext(Dispatchers.IO) {
        suspend fun fetch(path: String): List<SongDto> {
            return try {
                execute(Request.Builder().url("${config.apiBase()}$path").build()).use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "recommendations $path http ${resp.code}")
                        return emptyList()
                    }
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(SongDto.serializer()),
                        ResponseBodyReader.readText(resp.body).orEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "recommendations $path failed: ${e.message}")
                emptyList()
            }
        }

        val ranked = fetch("/ranking?days=3650").take(MAX_RECOMMENDATIONS)
        val newest = fetch("/songs/new").take(MAX_RECOMMENDATIONS)
        (ranked + newest).distinctBy { it.id }.take(MAX_RECOMMENDATIONS)
    }

    suspend fun fetchLibraryCount(): Long? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("${config.apiBase()}/library/status").build()).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "library status http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(LibraryStatus.serializer(), ResponseBodyReader.readText(resp.body).orEmpty()).totalSongs
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "library status failed: ${e.message}")
            null
        }
    }

    suspend fun fetchStandbyContent(): StandbyContent? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("${config.apiBase()}/standby/content").build()).use { resp ->
                if (!resp.isSuccessful) return@withContext null
                json.decodeFromString(StandbyContent.serializer(), ResponseBodyReader.readText(resp.body).orEmpty())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "standby content failed: ${e.message}")
            null
        }
    }

    suspend fun fetchReleaseInfo(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("${config.apiBase()}/release").build()).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "release info http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(ReleaseInfo.serializer(), ResponseBodyReader.readText(resp.body).orEmpty())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "release info failed: ${e.message}")
            null
        }
    }

    /** Streams an APK into a temporary file and only exposes it after a complete response. */
    suspend fun downloadApk(path: String, destination: File, expectedSize: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try {
            destination.parentFile?.mkdirs()
            temporary.delete()
            val url = if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                "${config.apiBase().removeSuffix("/api")}${if (path.startsWith('/')) path else "/$path"}"
            }
            execute(Request.Builder().url(url).build()).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "APK download http ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                temporary.outputStream().buffered().use { output ->
                    if (expectedSize > ResponseBodyReader.MAX_APK_BYTES ||
                        ResponseBodyReader.copyTo(body, output, ResponseBodyReader.MAX_APK_BYTES) == null
                    ) return@withContext false
                }
            }
            if (temporary.length() <= 0 || (expectedSize > 0 && temporary.length() != expectedSize)) {
                Log.w(TAG, "APK size mismatch: expected=$expectedSize actual=${temporary.length()}")
                return@withContext false
            }
            if (destination.exists() && !destination.delete()) return@withContext false
            temporary.renameTo(destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed: ${e.message}")
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun fetchUrl(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else "${config.apiBase().removeSuffix("/api")}$path"
            execute(Request.Builder().url(url).build()).use { response ->
                if (response.isSuccessful) ResponseBodyReader.readBytes(response.body) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    /** 拉流地址：http://host/api/stream/{fileId} */
    fun streamUrl(fileId: Long): String = "${config.apiBase()}/stream/$fileId"

    /** 二维码地址：http://host/api/qr?size=xxx（P1.30 待机页扫码引导） */
    fun qrUrl(size: Int): String = "${config.apiBase()}/qr?size=$size"

    /** 拉取二维码 PNG 字节；失败返回 null。 */
    suspend fun fetchQr(size: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url(qrUrl(size)).build()).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "qr http ${resp.code}")
                    return@withContext null
                }
                ResponseBodyReader.readBytes(resp.body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "qr fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    companion object {
        private const val TAG = "MediaApi"
        private const val MAX_RECOMMENDATIONS = 100
    }
}

