package com.homektv.tv.player

import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
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
)

internal interface PlaybackSource {
    suspend fun resolveFileSource(songId: Long): FileSourceResolution
    fun streamUrl(fileId: Long): String
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
            while (isCurrent(token)) {
                val result = try {
                    source.resolveFileSource(request.songId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    FileSourceResolution.Retryable(
                        KtvApiError(
                            kind = KtvApiErrorKind.NETWORK,
                            code = "FILE_SOURCE_RESOLUTION_FAILED",
                            message = error.message ?: "文件源暂时不可用",
                        ),
                    )
                }
                if (!isCurrent(token)) return@launch
                when (result) {
                    is FileSourceResolution.Ready -> {
                        val snapshot = desiredState.forQueue(request.queueId) ?: return@launch
                        if (!isCurrent(token)) return@launch
                        onFileReady(token, result.source, snapshot, source.streamUrl(result.source.id))
                        return@launch
                    }
                    is FileSourceResolution.Absent -> {
                        onMissingSource(token)
                        return@launch
                    }
                    is FileSourceResolution.Fatal -> {
                        onSourceFailure(token, result.error)
                        return@launch
                    }
                    is FileSourceResolution.ConfigurationFailure -> {
                        onSourceFailure(token, result.error)
                        return@launch
                    }
                    is FileSourceResolution.Retryable -> {
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

    companion object {
        val SOURCE_RETRY_DELAYS = longArrayOf(500L, 1_500L, 3_000L, 10_000L)
        const val MAX_SOURCE_RETRIES = 4
    }
}
