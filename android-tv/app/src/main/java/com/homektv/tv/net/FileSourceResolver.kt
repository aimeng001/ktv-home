package com.homektv.tv.net

import kotlinx.serialization.json.Json

/**
 * Resolves the playable file for a song without conflating transport failure
 * with a song that has no valid media.
 */
internal sealed interface FileSourceResolution {
    data class Ready(val source: FileSource) : FileSourceResolution
    data class Absent(val reason: AbsentReason) : FileSourceResolution
    data class Fatal(val error: KtvApiError) : FileSourceResolution
    data class ConfigurationFailure(val error: KtvApiError) : FileSourceResolution
    data class Retryable(val error: KtvApiError) : FileSourceResolution
}

internal enum class AbsentReason {
    SONG_NOT_FOUND,
    NO_VALID_FILE,
}

internal class FileSourceResolver(
    private val transport: KtvTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun resolve(songId: Long): FileSourceResolution =
        when (val response = transport.get("/songs/$songId")) {
            is KtvApiResult.Success -> decode(response.value)
            is KtvApiResult.Failure -> classifyFailure(response.error)
        }

    private fun classifyFailure(error: KtvApiError): FileSourceResolution = when {
        error.kind == KtvApiErrorKind.HTTP && error.status == 404 ->
            FileSourceResolution.Absent(AbsentReason.SONG_NOT_FOUND)
        error.kind == KtvApiErrorKind.HTTP && error.status in setOf(401, 403) ->
            FileSourceResolution.ConfigurationFailure(error)
        error.kind == KtvApiErrorKind.NETWORK ||
            (error.kind == KtvApiErrorKind.HTTP && isTransientHttp(error.status)) ->
            FileSourceResolution.Retryable(error)
        else -> FileSourceResolution.Fatal(error)
    }

    private fun isTransientHttp(status: Int?): Boolean =
        status == 408 || status == 425 || status == 429 || status != null && status >= 500

    private fun decode(body: String): FileSourceResolution =
        runCatching { json.decodeFromString(SongDetail.serializer(), body) }.fold(
            onSuccess = { detail ->
                val identified = detail.files.filter { it.id > 0L }
                when {
                    detail.files.isEmpty() ->
                        FileSourceResolution.Absent(AbsentReason.NO_VALID_FILE)

                    identified.isEmpty() ->
                        FileSourceResolution.Fatal(
                            KtvApiError(
                                kind = KtvApiErrorKind.DECODE,
                                code = "INVALID_FILE_SOURCE",
                                message = "歌曲文件源缺少有效 file id",
                            ),
                        )

                    else ->
                        // 服务端可能同时下发「未完成探测的高优先级行」和「可播放行」；
                        // 只按 priority 盲选会选中前者，随后 /api/stream 必然失败。
                        // ready == null 表示旧服务端，按可播放处理以保持兼容。
                        identified
                            .filter { it.ready != false }
                            .maxByOrNull { it.priority }
                            ?.let(FileSourceResolution::Ready)
                            ?: FileSourceResolution.Absent(AbsentReason.NO_VALID_FILE)
                }
            },
            onFailure = {
                FileSourceResolution.Fatal(
                    KtvApiError(
                        kind = KtvApiErrorKind.DECODE,
                        code = "SONG_DETAIL_DECODE",
                        message = "歌曲详情协议错误",
                    ),
                )
            },
        )
}
