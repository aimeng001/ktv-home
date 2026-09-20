package com.homektv.tv.player

enum class PlaybackEngineType {
    PRIMARY_MEDIA3,
    /** Legacy injected fallback kept for test doubles; production routing never uses it for video. */
    FALLBACK_FFMPEG,
    /** The server must provide a playable sidecar before Media3 is retried. */
    RESOLVE_REQUIRED,
    /** Kept for source compatibility with older test doubles. */
    UNSUPPORTED,
}

/** Routes unsupported containers/tracks to the server-side playback resolver. */
class DualEnginePlaybackRouter {
    private val softwareContainers = setOf("rm", "rmvb", "realmedia")

    fun selectEngine(videoCodec: String?, format: String?): PlaybackEngineType {
        if (format != null && softwareContainers.contains(format.lowercase())) {
            return PlaybackEngineType.RESOLVE_REQUIRED
        }
        return PlaybackEngineType.PRIMARY_MEDIA3
    }

    fun shouldFallbackOnTracks(
        hasVideoDeclared: Boolean,
        videoTrackCount: Int,
        hasSupportedVideoTrack: Boolean,
    ): Boolean = hasVideoDeclared && (videoTrackCount == 0 || !hasSupportedVideoTrack)
}
