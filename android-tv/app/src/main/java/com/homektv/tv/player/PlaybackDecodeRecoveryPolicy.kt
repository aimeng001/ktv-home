package com.homektv.tv.player

internal enum class PlaybackStallAction {
    NONE,
    IN_PLACE_SEEK,
    SKIP_SEEK_FOR_LIVE_PIPE,
    REQUEST_LIVE_TRANSCODE,
    REPORT_ERROR,
}

/** Whether a failed platform fallback should be escalated to the server's live pipe once. */
internal object PlaybackDecodeRecoveryPolicy {
    fun shouldRequestLiveTranscode(
        isVideo: Boolean,
        isAlreadyLiveTranscoded: Boolean,
        fileId: Long?,
        requestedFileId: Long?,
    ): Boolean = isVideo && !isAlreadyLiveTranscoded && fileId != null && fileId != requestedFileId
}

internal object PlaybackStallRecoveryPolicy {
    fun resolve(
        decision: VideoPlaybackWatchdog.Decision,
        isVideo: Boolean,
        isLiveTranscode: Boolean,
        fileId: Long?,
        requestedFileId: Long?,
    ): PlaybackStallAction = when (decision) {
        VideoPlaybackWatchdog.Decision.NONE -> PlaybackStallAction.NONE
        VideoPlaybackWatchdog.Decision.RECOVER ->
            if (isLiveTranscode) PlaybackStallAction.SKIP_SEEK_FOR_LIVE_PIPE else PlaybackStallAction.IN_PLACE_SEEK
        VideoPlaybackWatchdog.Decision.EXHAUSTED ->
            if (PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                    isVideo = isVideo,
                    isAlreadyLiveTranscoded = isLiveTranscode,
                    fileId = fileId,
                    requestedFileId = requestedFileId,
                )
            ) {
                PlaybackStallAction.REQUEST_LIVE_TRANSCODE
            } else {
                PlaybackStallAction.REPORT_ERROR
            }
    }
}

internal object PlaybackTransientRetryPolicy {
    const val STREAM_READ_TIMEOUT_SECONDS = 20L
    private val RETRY_DELAYS_MS = longArrayOf(750L, 1_500L, 2_500L, 3_500L)
    val maxRetries: Int get() = RETRY_DELAYS_MS.size

    fun delayForAttempt(attemptIndex: Int): Long =
        RETRY_DELAYS_MS[attemptIndex.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
}
