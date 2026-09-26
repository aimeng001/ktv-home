package com.homektv.tv.player

import android.media.MediaPlayer

/** A fallback failure keeps its user-facing reason separate from whether decode recovery is safe. */
data class FallbackPlaybackFailure(
    val message: String,
    val decoderOrFormatFailure: Boolean,
    val platformWhat: Int? = null,
    val platformExtra: Int? = null,
)

internal object FallbackPlaybackErrorPolicy {
    /** Only media-format errors, or a generic error after a known Media3 decoder failure, are decode recovery. */
    fun shouldRequestLiveTranscode(
        what: Int?,
        extra: Int?,
        precededByMedia3DecoderFailure: Boolean = false,
    ): Boolean =
        (what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == Int.MIN_VALUE) && when (extra) {
            MediaPlayer.MEDIA_ERROR_MALFORMED,
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> true
            0 -> precededByMedia3DecoderFailure
            else -> false
        }
}
