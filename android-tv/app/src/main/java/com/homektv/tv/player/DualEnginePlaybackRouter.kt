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

/** Routes unsupported containers/tracks to the software fallback player. */
class DualEnginePlaybackRouter {
    private val softwareContainers = setOf("rm", "rmvb", "realmedia")
    private val softwareCodecs = setOf("rv10", "rv20", "rv30", "rv40", "realvideo")

    fun selectEngine(videoCodec: String?, format: String?): PlaybackEngineType {
        // 服务端对老格式已在流式管道中统一封装为标准 H.264/AAC fMP4 流，
        // 电视端统一通过 Media3 原生硬解，享受硬件芯片加速与完整原伴唱切换能力
        return PlaybackEngineType.PRIMARY_MEDIA3
    }

    fun shouldFallbackOnTracks(
        hasVideoDeclared: Boolean,
        videoTrackCount: Int,
        hasSupportedVideoTrack: Boolean,
    ): Boolean = hasVideoDeclared && (videoTrackCount == 0 || !hasSupportedVideoTrack)
}
