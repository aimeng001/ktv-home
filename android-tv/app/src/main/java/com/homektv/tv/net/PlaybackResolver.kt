package com.homektv.tv.net

import kotlinx.serialization.json.Json

/**
 * Resolves authoritative native or transcode playback descriptors for a song.
 */
internal class PlaybackResolver(
    private val transport: KtvTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val apiBaseProvider: () -> String,
    private val fileSourceResolver: FileSourceResolver = FileSourceResolver(transport, json),
) {
    suspend fun resolve(songId: Long, forceTranscode: Boolean = false): PlaybackResolution {
        return when (val sourceResult = fileSourceResolver.resolve(songId)) {
            is FileSourceResolution.Ready -> {
                val suffix = if (forceTranscode) "?forceTranscode=true" else ""
                when (val response = transport.get("/playback/resolve/${sourceResult.source.id}$suffix")) {
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

    internal fun decodePlayback(source: FileSource, body: String): PlaybackResolution =
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

    internal fun classifyPlaybackFailure(source: FileSource, error: KtvApiError): PlaybackResolution = when {
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

    internal fun absolutePlaybackUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = apiBaseProvider().removeSuffix("/api")
        return base + "/" + path.removePrefix("/")
    }

    private fun streamUrl(fileId: Long): String = "${apiBaseProvider()}/stream/$fileId"
}
