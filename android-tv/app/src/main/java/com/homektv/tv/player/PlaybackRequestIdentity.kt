package com.homektv.tv.player

import androidx.media3.common.Player

internal data class PlaybackRequestIdentity(
    val queueId: Long?,
    val fileId: Long,
)

internal fun shouldReusePlaybackRequest(
    current: PlaybackRequestIdentity?,
    requested: PlaybackRequestIdentity,
    playbackState: Int,
): Boolean = current == requested && playbackState != Player.STATE_IDLE

internal enum class PlaybackPrepareAction {
    REUSE_ACTIVE,
    RESOLVE_REQUIRED,
    FALLBACK,
    START_PRIMARY,
}

internal data class PlaybackPrepareOutcome(
    val action: PlaybackPrepareAction,
    val continuesSameQueueItem: Boolean,
    val requested: PlaybackRequestIdentity,
)

/**
 * Coordinates atomic source identity and stream metadata updates across primary
 * Media3, fallback FFmpeg, and live-transcode recovery paths so an early exit
 * or active-request reuse never leaves mixed state from the previous song.
 */
internal class PlaybackSessionMetadata {
    var currentFileId: Long? = null
    var currentQueueId: Long? = null
    var currentRequest: PlaybackRequestIdentity? = null
    var currentHasVideoDeclared: Boolean = false
    var currentFormat: String? = null
    var currentStreamUrl: String? = null
    var currentStreamOffsetMs: Long = 0L
    var currentIsLiveTranscode: Boolean = false
    var currentSongDurationMs: Long = 0L
    var fallbackFollowedMedia3DecoderFailure: Boolean = false
    var videoFailureReportedFileId: Long? = null
    var resolutionRequestedFileId: Long? = null

    fun commitNewSource(
        queueId: Long?,
        fileId: Long,
        streamUrl: String,
        hasVideoDeclared: Boolean,
        format: String?,
        songDurationMs: Long,
    ) {
        currentQueueId = queueId
        currentFileId = fileId
        currentRequest = null
        currentSongDurationMs = songDurationMs
        currentHasVideoDeclared = hasVideoDeclared
        currentFormat = format
        currentStreamUrl = streamUrl
        currentIsLiveTranscode = LiveTranscodeStreamUrl.isLiveTranscode(streamUrl)
        currentStreamOffsetMs = LiveTranscodeStreamUrl.startPositionMs(streamUrl)
        videoFailureReportedFileId = null
        resolutionRequestedFileId = null
        fallbackFollowedMedia3DecoderFailure = false
    }

    fun preparePlay(
        queueId: Long?,
        fileId: Long,
        streamUrl: String,
        hasVideoDeclared: Boolean,
        format: String?,
        songDurationMs: Long,
        engineChoice: PlaybackEngineType,
        activeEngineType: PlaybackEngineType,
        primaryPlaybackState: Int,
    ): PlaybackPrepareOutcome {
        val continuesSameQueueItem = currentFileId == fileId && currentQueueId == queueId
        val requested = PlaybackRequestIdentity(queueId = queueId, fileId = fileId)
        if (engineChoice == PlaybackEngineType.PRIMARY_MEDIA3 &&
            activeEngineType == PlaybackEngineType.PRIMARY_MEDIA3 &&
            shouldReusePlaybackRequest(currentRequest, requested, primaryPlaybackState)
        ) {
            return PlaybackPrepareOutcome(
                action = PlaybackPrepareAction.REUSE_ACTIVE,
                continuesSameQueueItem = continuesSameQueueItem,
                requested = requested,
            )
        }

        commitNewSource(
            queueId = queueId,
            fileId = fileId,
            streamUrl = streamUrl,
            hasVideoDeclared = hasVideoDeclared,
            format = format,
            songDurationMs = songDurationMs,
        )

        val action = when (engineChoice) {
            PlaybackEngineType.RESOLVE_REQUIRED,
            PlaybackEngineType.UNSUPPORTED -> PlaybackPrepareAction.RESOLVE_REQUIRED
            PlaybackEngineType.FALLBACK_FFMPEG -> PlaybackPrepareAction.FALLBACK
            PlaybackEngineType.PRIMARY_MEDIA3 -> PlaybackPrepareAction.START_PRIMARY
        }
        return PlaybackPrepareOutcome(
            action = action,
            continuesSameQueueItem = continuesSameQueueItem,
            requested = requested,
        )
    }

    fun resetAll() {
        currentRequest = null
        currentQueueId = null
        currentFileId = null
        currentHasVideoDeclared = false
        currentFormat = null
        currentStreamUrl = null
        currentStreamOffsetMs = 0L
        currentIsLiveTranscode = false
        currentSongDurationMs = 0L
        videoFailureReportedFileId = null
        resolutionRequestedFileId = null
        fallbackFollowedMedia3DecoderFailure = false
    }
}
