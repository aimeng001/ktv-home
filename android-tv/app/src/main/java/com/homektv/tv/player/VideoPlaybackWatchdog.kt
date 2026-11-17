package com.homektv.tv.player

/**
 * Pure state machine for video startup/stall recovery. The caller supplies
 * elapsedRealtime values so tests and production use the same monotonic clock.
 */
internal class VideoPlaybackWatchdog(
    private val timeoutMs: Long = 3_000L,
    private val maxRecoveries: Int = 3,
) {
    enum class Decision { NONE, RECOVER, EXHAUSTED }

    private val lock = Any()
    private var generation = 0L
    private var selectedVideo = false
    private var playing = false
    private var waitingSinceMs: Long? = null
    private var lastFrameAtMs: Long? = null
    private var recoveries = 0
    private var exhaustedReported = false
    private var firstFrameRendered = false

    fun onMediaChanged(): Long = synchronized(lock) {
        generation += 1
        selectedVideo = false
        playing = false
        waitingSinceMs = null
        lastFrameAtMs = null
        recoveries = 0
        exhaustedReported = false
        firstFrameRendered = false
        generation
    }

    fun onTracksChanged(selectedVideo: Boolean, nowMs: Long) = synchronized(lock) {
        this.selectedVideo = selectedVideo
        if (!selectedVideo) {
            waitingSinceMs = null
            lastFrameAtMs = null
        } else if (playing && waitingSinceMs == null && lastFrameAtMs == null) {
            if (firstFrameRendered) {
                lastFrameAtMs = nowMs
            } else {
                waitingSinceMs = nowMs
            }
        }
    }

    fun onPlayingChanged(playing: Boolean, nowMs: Long) = synchronized(lock) {
        this.playing = playing
        if (!playing || !selectedVideo) {
            waitingSinceMs = null
            lastFrameAtMs = null
            return@synchronized
        }
        if (firstFrameRendered) {
            lastFrameAtMs = nowMs
            waitingSinceMs = null
        } else {
            waitingSinceMs = nowMs
            lastFrameAtMs = null
        }
    }

    fun onRenderedFirstFrame(frameGeneration: Long, nowMs: Long) = synchronized(lock) {
        if (frameGeneration != generation || !selectedVideo) return@synchronized
        firstFrameRendered = true
        waitingSinceMs = null
        lastFrameAtMs = nowMs
        recoveries = 0
        exhaustedReported = false
    }

    fun onFrameMetadata(frameGeneration: Long, nowMs: Long) = synchronized(lock) {
        if (frameGeneration != generation || !selectedVideo) return@synchronized
        if (firstFrameRendered || lastFrameAtMs != null) {
            lastFrameAtMs = nowMs
            // 只要视频帧重新上屏，说明此前的短暂卡顿已恢复，清零恢复计数避免整首歌累计误切转码。
            recoveries = 0
            exhaustedReported = false
        }
    }

    fun poll(nowMs: Long): Decision = synchronized(lock) {
        if (!playing || !selectedVideo) return@synchronized Decision.NONE
        val baseline = lastFrameAtMs ?: waitingSinceMs ?: return@synchronized Decision.NONE
        if (nowMs - baseline < timeoutMs) return@synchronized Decision.NONE
        if (recoveries < maxRecoveries) {
            recoveries++
            lastFrameAtMs = nowMs
            waitingSinceMs = nowMs
            return@synchronized Decision.RECOVER
        }
        if (!exhaustedReported) {
            exhaustedReported = true
            return@synchronized Decision.EXHAUSTED
        }
        Decision.NONE
    }
}
