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

    private var generation = 0L
    private var selectedVideo = false
    private var playing = false
    private var waitingSinceMs: Long? = null
    private var lastFrameAtMs: Long? = null
    private var recoveries = 0
    private var exhaustedReported = false
    private var firstFrameRendered = false

    fun onMediaChanged(): Long {
        generation += 1
        selectedVideo = false
        playing = false
        waitingSinceMs = null
        lastFrameAtMs = null
        recoveries = 0
        exhaustedReported = false
        firstFrameRendered = false
        return generation
    }

    fun onTracksChanged(selectedVideo: Boolean, nowMs: Long) {
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

    fun onPlayingChanged(playing: Boolean, nowMs: Long) {
        this.playing = playing
        if (!playing || !selectedVideo) {
            waitingSinceMs = null
            lastFrameAtMs = null
            return
        }
        if (firstFrameRendered) {
            lastFrameAtMs = nowMs
            waitingSinceMs = null
        } else {
            waitingSinceMs = nowMs
            lastFrameAtMs = null
        }
    }

    fun onRenderedFirstFrame(frameGeneration: Long, nowMs: Long) {
        if (frameGeneration != generation || !selectedVideo) return
        firstFrameRendered = true
        waitingSinceMs = null
        lastFrameAtMs = nowMs
        recoveries = 0
        exhaustedReported = false
    }

    fun onFrameMetadata(frameGeneration: Long, nowMs: Long) {
        if (frameGeneration != generation || !selectedVideo) return
        if (firstFrameRendered || lastFrameAtMs != null) {
            lastFrameAtMs = nowMs
        }
    }

    fun poll(nowMs: Long): Decision {
        if (!playing || !selectedVideo) return Decision.NONE
        val baseline = lastFrameAtMs ?: waitingSinceMs ?: return Decision.NONE
        if (nowMs - baseline < timeoutMs) return Decision.NONE
        if (recoveries < maxRecoveries) {
            recoveries++
            lastFrameAtMs = nowMs
            waitingSinceMs = nowMs
            return Decision.RECOVER
        }
        if (!exhaustedReported) {
            exhaustedReported = true
            return Decision.EXHAUSTED
        }
        return Decision.NONE
    }
}
