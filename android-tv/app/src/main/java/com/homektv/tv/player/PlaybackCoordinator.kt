package com.homektv.tv.player

import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.PlaybackDescriptor
import com.homektv.tv.net.PlaybackResolution
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.PlaybackErrorContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PlaybackReplacementRequest(
    val queueId: Long?,
    val songId: Long,
    val forceTranscode: Boolean = false,
)

internal interface PlaybackSource {
    suspend fun resolveFileSource(songId: Long): FileSourceResolution
    fun streamUrl(fileId: Long): String

    /** Compatibility bridge for tests and old transports; MediaApi overrides it. */
    suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean = false): PlaybackResolution =
        when (val result = resolveFileSource(songId)) {
            is FileSourceResolution.Ready -> PlaybackResolution.Ready(
                result.source,
                PlaybackDescriptor(
                    kind = "NATIVE",
                    status = "READY",
                    sourceFileId = result.source.id,
                    streamUrl = streamUrl(result.source.id),
                    audioTracks = result.source.audioTracks,
                    vocalTrackIndex = result.source.audioLayout.accompanimentTrackIndex ?: result.source.vocalTrackIndex,
                    audioLayout = result.source.audioLayout,
                ),
            )
            is FileSourceResolution.Absent -> PlaybackResolution.Absent(result.reason)
            is FileSourceResolution.Fatal -> PlaybackResolution.Fatal(result.error)
            is FileSourceResolution.ConfigurationFailure -> PlaybackResolution.ConfigurationFailure(result.error)
            is FileSourceResolution.Retryable -> PlaybackResolution.Retryable(result.error)
        }
}

internal data class PlaybackReplacementToken(
    val generation: Long,
    val request: PlaybackReplacementRequest,
)

internal data class PlaybackReplacementTicket(
    val token: PlaybackReplacementToken,
    val job: Job,
)

internal object PlaybackSourceFailurePolicy {
    /** Source resolution exhausted: report the queue item, without inventing a file id. */
    fun errorContext(token: PlaybackReplacementToken): PlaybackErrorContext =
        PlaybackErrorContext.missingSource(token.request.queueId)
}

/**
 * Owns the file-source critical path for one Activity session. Replacing a
 * queue item synchronously invalidates the old output before any REST await;
 * lyrics and artwork are intentionally outside this coordinator.
 */
internal class PlaybackCoordinator(
    private val source: PlaybackSource,
    private val desiredState: DesiredPlaybackState,
    private val scope: CoroutineScope,
    private val onBeginReplacement: (queueId: Long?) -> Unit,
    private val onFileReady: (
        token: PlaybackReplacementToken,
        file: FileSource,
        snapshot: QueueSnapshot,
        streamUrl: String,
    ) -> Unit,
    private val onMissingSource: (token: PlaybackReplacementToken) -> Unit,
    private val onWaitingForSource: (token: PlaybackReplacementToken, error: KtvApiError) -> Unit = { _, _ -> },
    private val onPlaybackPreparing: (token: PlaybackReplacementToken, descriptor: PlaybackDescriptor) -> Unit = { _, _ -> },
    private val onSourceFailure: (token: PlaybackReplacementToken, error: KtvApiError) -> Unit = { _, _ -> },
    private val onSourceExhausted: (token: PlaybackReplacementToken, error: KtvApiError) -> Unit = { _, _ -> },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var generation = 0L
    private var activeRequest: PlaybackReplacementRequest? = null
    private var activeJob: Job? = null

    fun replace(request: PlaybackReplacementRequest): PlaybackReplacementTicket {
        val nextGeneration = synchronized(lock) {
            activeJob?.cancel()
            generation += 1
            activeRequest = request
            generation
        }

        // This must happen before the coroutine reaches the first network await.
        val token = PlaybackReplacementToken(nextGeneration, request)
        onBeginReplacement(request.queueId)
        val job = scope.launch {
            var attempt = 0
            var preparingAttempt = 0
            var waitingReported = false
            while (isCurrent(token)) {
                val result = try {
                    source.resolvePlayback(request.songId, request.forceTranscode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    PlaybackResolution.Retryable(
                        KtvApiError(
                            kind = KtvApiErrorKind.NETWORK,
                            code = "FILE_SOURCE_RESOLUTION_FAILED",
                            message = error.message ?: "文件源暂时不可用",
                        ),
                    )
                }
                if (!isCurrent(token)) return@launch
                when (result) {
                    is PlaybackResolution.Ready -> {
                        val snapshot = desiredState.forQueue(request.queueId) ?: return@launch
                        if (!isCurrent(token)) return@launch
                        val streamUrl = result.descriptor.streamUrl
                            ?: source.streamUrl(result.source.id)
                        onFileReady(token, result.playableFile(), snapshot, streamUrl)
                        return@launch
                    }
                    is PlaybackResolution.Preparing -> {
                        if (preparingAttempt >= MAX_PLAYBACK_RETRIES) {
                            onSourceExhausted(
                                token,
                                KtvApiError(KtvApiErrorKind.HTTP, "PLAYBACK_PREPARING_TIMEOUT", "MV 准备超时", status = 504),
                            )
                            return@launch
                        }
                        onPlaybackPreparing(token, result.descriptor)
                        preparingAttempt++
                        retryDelay(PLAYBACK_RETRY_DELAY_MS)
                    }
                    is PlaybackResolution.Failed -> {
                        onSourceFailure(token, result.error)
                        return@launch
                    }
                    is PlaybackResolution.Absent -> {
                        onMissingSource(token)
                        return@launch
                    }
                    is PlaybackResolution.Fatal -> {
                        onSourceFailure(token, result.error)
                        return@launch
                    }
                    is PlaybackResolution.ConfigurationFailure -> {
                        onSourceFailure(token, result.error)
                        return@launch
                    }
                    is PlaybackResolution.Retryable -> {
                        if (attempt >= MAX_SOURCE_RETRIES) {
                            onSourceExhausted(token, result.error)
                            return@launch
                        }
                        if (!waitingReported) {
                            waitingReported = true
                            onWaitingForSource(token, result.error)
                        }
                        val delayMs = SOURCE_RETRY_DELAYS[attempt.coerceAtMost(SOURCE_RETRY_DELAYS.lastIndex)]
                        attempt++
                        retryDelay(delayMs)
                    }
                }
            }
        }
        synchronized(lock) {
            if (generation == nextGeneration && activeRequest == request) activeJob = job
            else job.cancel()
        }
        return PlaybackReplacementTicket(token, job)
    }

    fun invalidate() {
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = null
            activeRequest = null
            generation += 1
        }
    }

    fun isCurrent(ticket: PlaybackReplacementTicket): Boolean = isCurrent(ticket.token)

    fun isCurrent(token: PlaybackReplacementToken): Boolean =
        synchronized(lock) {
            generation == token.generation && activeRequest == token.request
        }

    private fun PlaybackResolution.Ready.playableFile(): FileSource {
        val descriptor = descriptor
        if (!descriptor.kind.equals("TRANSCODE", ignoreCase = true) || descriptor.variantId == null) {
            return source
        }
        return source.copy(
            id = descriptor.variantId,
            format = "mp4",
            audioTracks = descriptor.audioTracks.coerceAtLeast(1),
            vocalTrackIndex = descriptor.vocalTrackIndex,
            audioLayout = descriptor.audioLayout,
            ready = true,
        )
    }

    companion object {
        val SOURCE_RETRY_DELAYS = longArrayOf(500L, 1_500L, 3_000L, 10_000L)
        const val MAX_SOURCE_RETRIES = 4
        const val MAX_PLAYBACK_RETRIES = 120
        const val PLAYBACK_RETRY_DELAY_MS = 1_000L
    }
}
