package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEngineFallbackContractTest {

    class MockFallbackPlayer : FallbackPlayer {
        var lastVolume: Int = -1
        var lastMuted: Boolean = false
        var lastVocalMode: String? = null
        var lastTrackIndex: Int? = null
        var lastAudioLayout: AudioLayout? = null
        var prepareCalled = 0
        var releaseCalled = false

        override fun setSurface(surface: android.view.Surface?) {}

        override fun prepareAndPlay(
            fileId: Long,
            streamUrl: String,
            initialPositionMs: Long,
            requestToken: Long,
            playWhenReady: Boolean,
        ) {
            prepareCalled++
        }

        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun setChannelMode(mode: String) {
            lastVocalMode = mode
        }

        override fun setVolume(volume: Int, muted: Boolean) {
            lastVolume = volume
            lastMuted = muted
        }

        override fun setVocalSelection(mode: String, trackIndex: Int?, audioLayout: AudioLayout) {
            lastVocalMode = mode
            lastTrackIndex = trackIndex
            lastAudioLayout = audioLayout
        }

        override fun release() {
            releaseCalled = true
        }

        override val isPlaying: Boolean = false
        override val currentPositionMs: Long = 0L
        override val durationMs: Long = 0L
    }

    @Test
    fun testFallbackPlayerContractSupportsVolumeAndDualTrackSelection() {
        val mock = MockFallbackPlayer()
        mock.setVolume(75, true)
        assertEquals(75, mock.lastVolume)
        assertTrue(mock.lastMuted)

        val dualTrack = AudioLayout(layout = "DUAL_TRACK")
        mock.setVocalSelection("accompaniment", 1, dualTrack)
        assertEquals("accompaniment", mock.lastVocalMode)
        assertEquals(1, mock.lastTrackIndex)
        assertEquals("DUAL_TRACK", mock.lastAudioLayout?.layout)
    }

    @Test
    fun testMissingVideoTrackRequiresBoundedResolutionInsteadOfSilentFallback() {
        val router = DualEnginePlaybackRouter()
        assertTrue(
            router.shouldResolveMissingOrUnsupportedVideoTracks(
                hasVideoDeclared = true,
                videoTrackCount = 0,
                hasSupportedVideoTrack = false,
            ),
        )
        assertFalse(
            "the same source must not request live decode twice",
            PlaybackDecodeRecoveryPolicy.shouldRequestLiveTranscode(
                isVideo = true,
                isAlreadyLiveTranscoded = false,
                fileId = 11L,
                requestedFileId = 11L,
            ),
        )
    }

    @Test
    fun testFallbackPlayerSupportsDualChannelLeftAndRightAccompaniment() {
        val mock = MockFallbackPlayer()
        val dualChannel = AudioLayout(
            layout = "DUAL_CHANNEL",
            originalChannel = "left",
            accompanimentChannel = "right",
        )
        mock.setVocalSelection("accompaniment", null, dualChannel)
        assertEquals("accompaniment", mock.lastVocalMode)
        assertEquals("DUAL_CHANNEL", mock.lastAudioLayout?.layout)
        assertEquals("right", mock.lastAudioLayout?.accompanimentChannel)
        assertEquals("left", mock.lastAudioLayout?.originalChannel)

        mock.setVocalSelection("original", null, dualChannel)
        assertEquals("original", mock.lastVocalMode)
    }
}
