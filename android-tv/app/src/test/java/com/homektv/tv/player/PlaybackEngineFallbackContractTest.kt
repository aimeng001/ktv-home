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
        val calls = mutableListOf<String>()
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
            calls += "prepare"
        }

        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun setChannelMode(mode: String) {
            calls += "channelMode"
            lastVocalMode = mode
        }

        override fun setVolume(volume: Int, muted: Boolean) {
            lastVolume = volume
            lastMuted = muted
        }

        override fun setVocalSelection(mode: String, trackIndex: Int?, audioLayout: AudioLayout) {
            calls += "vocalSelection"
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

    @Test
    fun fallbackHandoffPreparesThenPassesTheCurrentDualTrackSelection() {
        val mock = MockFallbackPlayer()
        val layout = AudioLayout(
            layout = "DUAL_TRACK",
            originalTrackIndex = 0,
            accompanimentTrackIndex = 2,
        )

        prepareFallbackPlayback(
            player = mock,
            fileId = 41,
            streamUrl = "http://server/api/stream/5",
            initialPositionMs = 12_000,
            requestToken = 7,
            playWhenReady = true,
            mode = "accompaniment",
            accompanimentIndex = 1,
            audioTrackCount = 3,
            audioLayout = layout,
        )

        assertEquals(listOf("prepare", "channelMode", "vocalSelection"), mock.calls)
        assertEquals(1, mock.prepareCalled)
        assertEquals("accompaniment", mock.lastVocalMode)
        assertEquals(2, mock.lastTrackIndex)
        assertEquals(layout, mock.lastAudioLayout)
    }

    @Test
    fun resolvePlaybackDurationMs_falls_back_to_metadata_when_engine_duration_non_positive() {
        assertEquals(210_000L, resolvePlaybackDurationMs(engineDurationMs = 0L, metadataDurationMs = 210_000L))
        assertEquals(210_000L, resolvePlaybackDurationMs(engineDurationMs = Long.MIN_VALUE + 1L, metadataDurationMs = 210_000L))
        assertEquals(185_500L, resolvePlaybackDurationMs(engineDurationMs = 185_500L, metadataDurationMs = 210_000L))
        assertEquals(0L, resolvePlaybackDurationMs(engineDurationMs = 0L, metadataDurationMs = 0L))
        assertEquals(0L, resolvePlaybackDurationMs(engineDurationMs = -1L, metadataDurationMs = -10L))
    }

    @Test
    fun playbackEngineReleaseAndDetachGuardLazyExoPlayerInitialization() {
        val source = sequenceOf(
            java.io.File("src/main/java/com/homektv/tv/player/PlaybackEngine.kt"),
            java.io.File("android-tv/app/src/main/java/com/homektv/tv/player/PlaybackEngine.kt"),
            java.io.File("../app/src/main/java/com/homektv/tv/player/PlaybackEngine.kt"),
        ).firstOrNull(java.io.File::isFile)
            ?: error("Cannot locate PlaybackEngine.kt")
        val text = source.readText()
        val releaseBlock = text.substringAfter("fun release()").substringBefore("private val progressTicker")
        val detachBlock = text.substringAfter("fun detach(view: PlayerView)").substringBefore("private fun bindFallbackSurface")

        assertTrue(
            "release() must check playerDelegate.isInitialized() instead of instantiating ExoPlayer just to release it",
            releaseBlock.contains("if (playerDelegate.isInitialized())"),
        )
        assertTrue(
            "detach() must check playerDelegate.isInitialized() before touching view.player === player",
            detachBlock.contains("if (playerDelegate.isInitialized()"),
        )
    }

    @Test
    fun resolvePlaybackStreamVolumePercentHandlesNonPositiveMaxWithoutDividingByZero() {
        assertEquals(null, resolvePlaybackStreamVolumePercent(index = 5, max = 0))
        assertEquals(null, resolvePlaybackStreamVolumePercent(index = 5, max = -1))
        assertEquals(50, resolvePlaybackStreamVolumePercent(index = 5, max = 10))
        assertEquals(100, resolvePlaybackStreamVolumePercent(index = 15, max = 10))
        assertEquals(0, resolvePlaybackStreamVolumePercent(index = -2, max = 10))
    }

    @Test
    fun playbackEngineDebugLoggerVolumeAndMicContractsHold() {
        val engineSource = locateMain("src/main/java/com/homektv/tv/player/PlaybackEngine.kt").readText()
        assertTrue(
            "EventLogger must be guarded by BuildConfig.DEBUG",
            engineSource.contains("if (BuildConfig.DEBUG)") && engineSource.contains("EventLogger()"),
        )
        val applyVolumeBlock = engineSource.substringAfter("fun applyVolume(").substringBefore("fun setPitchSemitones(")
        assertTrue(
            "applyVolume must guard playerDelegate.isInitialized()",
            applyVolumeBlock.contains("if (playerDelegate.isInitialized())"),
        )

        val effectSource = locateMain("src/main/java/com/homektv/tv/ui/EffectOverlayView.kt").readText()
        assertTrue(
            "EffectOverlayView must scope software layer to Android versions below P",
            effectSource.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.P"),
        )

        val micSource = locateMain("src/main/java/com/homektv/tv/player/MicrophoneMonitor.kt").readText()
        val startBlock = micSource.substringAfter("fun start(): Boolean").substringBefore("private fun defaultRoutedInput()")
        val permissionIdx = startBlock.indexOf("Manifest.permission.RECORD_AUDIO")
        val stopIdx = startBlock.indexOf("sessionCoordinator.stop()")
        assertTrue(
            "MicrophoneMonitor.start() must validate permissions before stopping an active session",
            permissionIdx >= 0 && (stopIdx < 0 || stopIdx > permissionIdx),
        )
        assertTrue(
            "MicrophoneMonitor read loop must break on negative AudioRecord read error",
            micSource.contains("if (read < 0) break"),
        )
    }

    private fun locateMain(relativePath: String): java.io.File = sequenceOf(
        java.io.File(relativePath),
        java.io.File("android-tv/app/$relativePath"),
        java.io.File("../app/$relativePath"),
    ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate $relativePath")
}
