package com.homektv.tv.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.util.concurrent.TimeUnit

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
class MediaApi(private val config: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 拉歌曲详情，返回 priority 最高的文件源；失败或无文件返回 null。
     *
     * Fetches song details and returns the highest-priority file source, or null on failure.
     */
    suspend fun bestFileSource(songId: Long): FileSource? = withContext(Dispatchers.IO) {
        val url = "${config.apiBase()}/songs/$songId"
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "detail $songId http ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val detail = json.decodeFromString(SongDetail.serializer(), body)
                detail.files.maxByOrNull { it.priority }
            }
        } catch (e: Exception) {
            Log.w(TAG, "detail $songId failed: ${e.message}")
            null
        }
    }

    suspend fun fetchLyric(songId: Long): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/lyric/$songId").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    suspend fun fetchCover(songId: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/cover/$songId").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) { null }
    }

    suspend fun control(action: String, params: String = "{}"): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = "{\"action\":\"$action\",\"params\":$params}".toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("${config.apiBase()}/control").post(body).build()).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun fetchRecommendations(): List<SongDto> = withContext(Dispatchers.IO) {
        fun fetch(path: String): List<SongDto> {
            return try {
                http.newCall(Request.Builder().url("${config.apiBase()}$path").build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "recommendations $path http ${resp.code}")
                        return emptyList()
                    }
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(SongDto.serializer()),
                        resp.body?.string().orEmpty(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "recommendations $path failed: ${e.message}")
                emptyList()
            }
        }

        val ranked = fetch("/ranking?days=3650")
        val newest = fetch("/songs/new")
        (ranked + newest).distinctBy { it.id }
    }

    suspend fun fetchLibraryCount(): Long? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/library/status").build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "library status http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(LibraryStatus.serializer(), resp.body?.string().orEmpty()).totalSongs
            }
        } catch (e: Exception) {
            Log.w(TAG, "library status failed: ${e.message}")
            null
        }
    }

    suspend fun fetchStandbyContent(): StandbyContent = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/standby/content").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext StandbyContent()
                json.decodeFromString(StandbyContent.serializer(), resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "standby content failed: ${e.message}")
            StandbyContent()
        }
    }

    suspend fun fetchReleaseInfo(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/release").build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "release info http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(ReleaseInfo.serializer(), resp.body?.string().orEmpty())
            }
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
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "APK download http ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                temporary.outputStream().buffered().use { output -> body.byteStream().use { it.copyTo(output) } }
            }
            if (temporary.length() <= 0 || (expectedSize > 0 && temporary.length() != expectedSize)) {
                Log.w(TAG, "APK size mismatch: expected=$expectedSize actual=${temporary.length()}")
                return@withContext false
            }
            if (destination.exists() && !destination.delete()) return@withContext false
            temporary.renameTo(destination)
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
            http.newCall(Request.Builder().url(url).build()).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
        } catch (_: Exception) { null }
    }

    /** 拉流地址：http://host/api/stream/{fileId} */
    fun streamUrl(fileId: Long): String = "${config.apiBase()}/stream/$fileId"

    /** 二维码地址：http://host/api/qr?size=xxx（P1.30 待机页扫码引导） */
    fun qrUrl(size: Int): String = "${config.apiBase()}/qr?size=$size"

    /** 拉取二维码 PNG 字节；失败返回 null。 */
    suspend fun fetchQr(size: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(qrUrl(size)).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "qr http ${resp.code}")
                    return@withContext null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "qr fetch failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MediaApi"
    }
}
