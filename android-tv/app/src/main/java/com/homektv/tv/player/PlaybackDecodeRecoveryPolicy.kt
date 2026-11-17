package com.homektv.tv.player

import androidx.media3.common.PlaybackException

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

internal object PlaybackDecodeErrorPolicy {
    fun isCodecLevelError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> true
        else -> false
    }

    fun isDecoderOrFormatError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_TIMEOUT -> true
        else -> isCodecLevelError(errorCode)
    }

    fun isTransientIoError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true
        else -> false
    }
}
