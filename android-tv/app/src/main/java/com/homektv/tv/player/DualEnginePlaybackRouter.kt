package com.homektv.tv.player

enum class PlaybackEngineType {
    PRIMARY_MEDIA3,
    /** Legacy injected fallback kept for test doubles; production routing never uses it for video. */
    FALLBACK_FFMPEG,
    UNSUPPORTED,
}

/**
 * 播放双引擎路由器。
 * - 99% 的现代视频（H.264 / HEVC / MPEG-2 等）：走系统 Media3 + 芯片级硬解，保持零发热、极低功耗与原伴唱音频链路；
 * - rv40/rv30 等 MKV 视频走已打包的 Media3 FFmpeg 扩展；真正不支持的容器失败关闭，禁止仅播音频。
 */
class DualEnginePlaybackRouter {
    private val softwareContainers = setOf("rm", "rmvb")

    fun selectEngine(videoCodec: String?, format: String?): PlaybackEngineType {
        // The app packages Media3 FFmpeg extension renderers (including rv40/rv30).
        // Sending these files to Android MediaPlayer silently drops the video track.
        if (format != null && softwareContainers.contains(format.lowercase())) {
            return PlaybackEngineType.UNSUPPORTED
        }
        return PlaybackEngineType.PRIMARY_MEDIA3
    }

    fun shouldFallbackOnTracks(
        hasVideoDeclared: Boolean,
        videoTrackCount: Int,
        hasSupportedVideoTrack: Boolean,
    ): Boolean {
        // 如果文件已声明为视频（具有有效分辨率），但 Media3 解封装器静默过滤或解码不支持导致可用视频轨为 0，
        // 自动触发客户端 FFmpeg 软解引擎 Fallback
        if (hasVideoDeclared && (videoTrackCount == 0 || !hasSupportedVideoTrack)) {
            return true
        }
        return false
    }
}
