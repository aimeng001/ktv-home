package com.homektv.tv.net

/** Stable identity captured when a playback error is reported. */
data class PlaybackErrorContext(
    val queueId: Long?,
    val fileId: Long?,
) {
    companion object {
        fun forPlayback(queueId: Long?, fileId: Long?) = PlaybackErrorContext(queueId, fileId)

        fun missingSource(queueId: Long?) = PlaybackErrorContext(queueId, null)
    }
}
