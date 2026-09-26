package com.homektv.tv.player

import android.os.Handler
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Creates the bundled software video renderer only when this APK ABI has its native library. */
@androidx.media3.common.util.UnstableApi
internal fun createFfmpegVideoRenderer(
    allowedVideoJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener?,
): Renderer? {
    val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
    if (!available) return null
    return ExperimentalFfmpegVideoRenderer(
        allowedVideoJoiningTimeMs,
        eventHandler,
        eventListener,
        /* maxDroppedFramesToNotify= */ 50,
    )
}
