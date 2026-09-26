package com.homektv.tv.player

/** Whether a failed platform fallback should be escalated to the server's live pipe once. */
internal object PlaybackDecodeRecoveryPolicy {
    fun shouldRequestLiveTranscode(
        isVideo: Boolean,
        isAlreadyLiveTranscoded: Boolean,
        fileId: Long?,
        requestedFileId: Long?,
    ): Boolean = isVideo && !isAlreadyLiveTranscoded && fileId != null && fileId != requestedFileId
}
