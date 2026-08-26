package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackIndexResolverTest {
    @Test
    fun originalUsesItsExplicitTrackIndex() {
        val layout = AudioLayout(
            layout = "DUAL_TRACK",
            originalTrackIndex = 2,
            accompanimentTrackIndex = 1,
        )

        assertEquals(2, resolveAudioTrackIndex(layout, "original", 3, legacyAccompanimentIndex = 1))
    }

    @Test
    fun accompanimentUsesItsExplicitTrackIndex() {
        val layout = AudioLayout(
            layout = "DUAL_TRACK",
            originalTrackIndex = 2,
            accompanimentTrackIndex = 1,
        )

        assertEquals(1, resolveAudioTrackIndex(layout, "accompaniment", 3, legacyAccompanimentIndex = 0))
    }

    @Test
    fun legacyTwoTrackFallbackRemainsAvailable() {
        val layout = AudioLayout(layout = "DUAL_TRACK")

        assertEquals(1, resolveAudioTrackIndex(layout, "original", 2, legacyAccompanimentIndex = 0))
        assertEquals(0, resolveAudioTrackIndex(layout, "accompaniment", 2, legacyAccompanimentIndex = 0))
    }

    @Test
    fun invalidExplicitIndexFallsBackWithoutSelectingAnUnavailableTrack() {
        val layout = AudioLayout(
            layout = "DUAL_TRACK",
            originalTrackIndex = 8,
            accompanimentTrackIndex = -1,
        )

        assertEquals(1, resolveAudioTrackIndex(layout, "original", 2, legacyAccompanimentIndex = 0))
        assertNull(resolveAudioTrackIndex(layout, "accompaniment", 2, legacyAccompanimentIndex = -1))
    }

    @Test
    fun noAudioTracksProducesNoSelection() {
        assertNull(resolveAudioTrackIndex(AudioLayout(layout = "DUAL_TRACK"), "original", 0, 0))
    }
}
