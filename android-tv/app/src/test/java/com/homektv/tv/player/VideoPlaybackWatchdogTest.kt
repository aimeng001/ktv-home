package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackWatchdogTest {
    @Test
    fun startupWithoutFirstFrameRequestsBoundedRecovery() {
        val watchdog = VideoPlaybackWatchdog(timeoutMs = 3_000L, maxRecoveries = 2)
        val generation = watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = true, nowMs = 100L)
        watchdog.onPlayingChanged(playing = true, nowMs = 100L)

        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(3_099L))
        assertEquals(VideoPlaybackWatchdog.Decision.RECOVER, watchdog.poll(3_100L))
        assertEquals(VideoPlaybackWatchdog.Decision.RECOVER, watchdog.poll(6_100L))
        assertEquals(VideoPlaybackWatchdog.Decision.EXHAUSTED, watchdog.poll(9_100L))
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(12_100L))
        watchdog.onRenderedFirstFrame(generation, 12_200L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(15_199L))
        assertEquals(VideoPlaybackWatchdog.Decision.RECOVER, watchdog.poll(15_200L))
    }

    @Test
    fun bufferingOrPauseDoesNotCountAsVideoStall() {
        val watchdog = VideoPlaybackWatchdog(timeoutMs = 3_000L)
        watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = true, nowMs = 0L)
        watchdog.onPlayingChanged(playing = false, nowMs = 0L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(20_000L))
        watchdog.onPlayingChanged(playing = true, nowMs = 20_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(22_999L))
    }

    @Test
    fun renderedFrameResetsTimerAndAudioOnlyNeverStalls() {
        val watchdog = VideoPlaybackWatchdog(timeoutMs = 3_000L)
        val generation = watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = true, nowMs = 0L)
        watchdog.onPlayingChanged(playing = true, nowMs = 0L)
        watchdog.onRenderedFirstFrame(generation, 1_000L)
        watchdog.onFrameMetadata(generation, 2_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(4_999L))
        assertEquals(VideoPlaybackWatchdog.Decision.RECOVER, watchdog.poll(5_000L))

        watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = false, nowMs = 10_000L)
        watchdog.onPlayingChanged(playing = true, nowMs = 10_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(99_999L))
    }

    @Test
    fun staleGenerationFramesCannotSatisfyCurrentMedia() {
        val watchdog = VideoPlaybackWatchdog(timeoutMs = 3_000L)
        val oldGeneration = watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = true, nowMs = 0L)
        watchdog.onPlayingChanged(playing = true, nowMs = 0L)
        val newGeneration = watchdog.onMediaChanged()
        watchdog.onRenderedFirstFrame(oldGeneration, 1_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(2_999L))
        watchdog.onRenderedFirstFrame(newGeneration, 2_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(4_999L))
    }

    @Test
    fun framesAfterPauseResumeKeepPlayingWithoutRecovery() {
        val watchdog = VideoPlaybackWatchdog(timeoutMs = 3_000L)
        val generation = watchdog.onMediaChanged()
        watchdog.onTracksChanged(selectedVideo = true, nowMs = 0L)
        watchdog.onPlayingChanged(playing = true, nowMs = 0L)
        watchdog.onRenderedFirstFrame(generation, 100L)

        // Pause
        watchdog.onPlayingChanged(playing = false, nowMs = 2_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(10_000L))

        // Resume: Media3 may not invoke onRenderedFirstFrame again, only onFrameMetadata
        watchdog.onPlayingChanged(playing = true, nowMs = 10_000L)
        watchdog.onFrameMetadata(generation, 11_000L)
        watchdog.onFrameMetadata(generation, 12_000L)
        watchdog.onFrameMetadata(generation, 13_000L)

        // Should NOT recover at 13_500L (last frame was 13_000L, only 500ms elapsed)
        assertEquals(VideoPlaybackWatchdog.Decision.NONE, watchdog.poll(13_500L))

        // Stalls if 3000ms pass since last frame (13_000 + 3_000 = 16_000L)
        assertEquals(VideoPlaybackWatchdog.Decision.RECOVER, watchdog.poll(16_000L))
    }
}
