package com.homektv.tv.player

import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.PlaybackDescriptor
import com.homektv.tv.net.PlaybackResolution
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.PlaybackErrorContext
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PlaybackReplacementRequest(
    val queueId: Long?,
    val songId: Long,
    val forceTranscode: Boolean = false,
    val recoveryPositionMs: Long? = null,
    val recoverySeekSequence: Long? = null,
    val recoveryPlayWhenReady: Boolean? = null,
    val stateAtFailure: String? = null,
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
            var waitingReported = false
            var preparingReported = false
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
                        val liveTranscode = result.descriptor.kind.equals("LIVE_TRANSCODE", ignoreCase = true)
                        val playbackSnapshot = snapshot.withRecoveryState(request, liveTranscode)
                        val resolvedUrl = result.descriptor.streamUrl
                            ?: source.streamUrl(result.source.id)
                        val streamUrl = if (liveTranscode && playbackSnapshot.positionMs > 0L) {
                            LiveTranscodeStreamUrl.withStart(resolvedUrl, playbackSnapshot.positionMs)
                        } else resolvedUrl
                        onFileReady(token, result.playableFile(), playbackSnapshot, streamUrl)
                        return@launch
                    }
                    is PlaybackResolution.Preparing -> {
                        // A slow transcode is still a valid in-progress playback. Keep polling
                        // while this queue item is current; only READY, FAILED, cancellation,
                        // or an explicit queue replacement may end this wait.
                        if (!preparingReported) {
                            preparingReported = true
                            onPlaybackPreparing(token, result.descriptor)
                        }
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
        if (descriptor.kind.equals("LIVE_TRANSCODE", ignoreCase = true)) {
            return source.copy(
                format = "mp4",
                audioTracks = descriptor.audioTracks.coerceAtLeast(1),
                vocalTrackIndex = descriptor.vocalTrackIndex,
                audioLayout = descriptor.audioLayout,
                ready = true,
            )
        }
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

    private fun QueueSnapshot.withRecoveryState(
        request: PlaybackReplacementRequest,
        liveTranscode: Boolean,
    ): QueueSnapshot {
        if (!liveTranscode || !request.forceTranscode) return this
        val seekUnchanged = request.recoverySeekSequence == null ||
            request.recoverySeekSequence == seekSequence
        val stateUnchanged = request.stateAtFailure == null ||
            request.stateAtFailure.equals(state, ignoreCase = true)
        return copy(
            positionMs = if (seekUnchanged && request.recoveryPositionMs != null) {
                maxOf(positionMs, request.recoveryPositionMs.coerceAtLeast(0L))
            } else positionMs,
            state = if (stateUnchanged && request.recoveryPlayWhenReady != null) {
                if (request.recoveryPlayWhenReady) "playing" else "paused"
            } else state,
        )
    }

    companion object {
        val SOURCE_RETRY_DELAYS = longArrayOf(500L, 1_500L, 3_000L, 10_000L)
        const val MAX_SOURCE_RETRIES = 4
        const val PLAYBACK_RETRY_DELAY_MS = 1_000L
    }
}

/** Utilities for the server's no-disk-cache, seekable live fMP4 pipe. */
internal object LiveTranscodeStreamUrl {
    fun withStart(streamUrl: String, positionMs: Long): String {
        val withoutOldStart = withoutStart(streamUrl)
        if (positionMs <= 0L) return withoutOldStart
        val separator = if ('?' in withoutOldStart) '&' else '?'
        val seconds = String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1_000.0)
        return "$withoutOldStart${separator}start=$seconds"
    }

    fun isLiveTranscode(streamUrl: String?): Boolean =
        streamUrl?.substringAfter('?', "")?.split('&')?.any { it == "transcode=true" } == true

    fun startPositionMs(streamUrl: String?): Long {
        val start = streamUrl?.substringAfter('?', "")?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "start" }
            ?.substringAfter('=')
            ?.toDoubleOrNull()
            ?: return 0L
        if (!start.isFinite() || start <= 0.0) return 0L
        return (start * 1_000.0).toLong()
    }

    private fun withoutStart(streamUrl: String): String {
        val path = streamUrl.substringBefore('?')
        val query = streamUrl.substringAfter('?', "")
            .split('&')
            .filter(String::isNotBlank)
            .filterNot { it.substringBefore('=') == "start" }
        return if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
    }
}

internal object LiveTranscodePosition {
    fun absolutePositionMs(pipePositionMs: Long, basePositionMs: Long): Long =
        pipePositionMs.coerceAtLeast(0L) + basePositionMs.coerceAtLeast(0L)

    fun pipePositionMs(absolutePositionMs: Long, basePositionMs: Long): Long =
        (absolutePositionMs.coerceAtLeast(0L) - basePositionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
}
