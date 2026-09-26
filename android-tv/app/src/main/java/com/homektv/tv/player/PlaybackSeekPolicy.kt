package com.homektv.tv.player

/** Live FFmpeg pipes are not seekable in-place; seeking must request a new pipe at the target offset. */
internal object PlaybackSeekPolicy {
    fun shouldReopenLivePipe(isLiveTranscoded: Boolean, engineType: PlaybackEngineType): Boolean =
        isLiveTranscoded && when (engineType) {
            PlaybackEngineType.PRIMARY_MEDIA3,
            PlaybackEngineType.FALLBACK_FFMPEG -> true
            PlaybackEngineType.RESOLVE_REQUIRED,
            PlaybackEngineType.UNSUPPORTED -> false
        }
}
