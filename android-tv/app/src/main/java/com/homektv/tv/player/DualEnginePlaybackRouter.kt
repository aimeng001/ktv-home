package com.homektv.tv.player

enum class PlaybackEngineType {
    PRIMARY_MEDIA3,
    FALLBACK_FFMPEG,
}

/**
 * 播放双引擎路由器。
 * - 99% 的现代视频（H.264 / HEVC / MPEG-2 等）：走系统 Media3 + 芯片级硬解，保持零发热、极低功耗与原伴唱音频链路；
 * - 老旧生僻视频（rv40 / rv30 / rmvb / wmv 等）：在解封装或解码受阻时，自动调度端侧 FFmpeg 全格式软解备选引擎。
 */
class DualEnginePlaybackRouter {
    private val softwareVideoCodecs = setOf("rv40", "rv30", "rv20", "rmvb", "wmv2", "cook", "flv")
    private val softwareContainers = setOf("rm", "rmvb")

    fun selectEngine(videoCodec: String?, format: String?): PlaybackEngineType {
        if (videoCodec != null && softwareVideoCodecs.contains(videoCodec.lowercase())) {
            return PlaybackEngineType.FALLBACK_FFMPEG
        }
        if (format != null && softwareContainers.contains(format.lowercase())) {
            return PlaybackEngineType.FALLBACK_FFMPEG
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
