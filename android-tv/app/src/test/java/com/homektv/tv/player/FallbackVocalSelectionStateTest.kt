package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackVocalSelectionStateTest {
    @Test
    fun vocalSelectionRequestedBeforeAsyncPrepareIsReappliedAfterPrepare() {
        val state = FallbackVocalSelectionState()
        val layout = AudioLayout(
            layout = "DUAL_CHANNEL",
            originalChannel = "RIGHT",
            accompanimentChannel = "LEFT",
        )
        state.beginPreparation()

        assertNull(state.select("accompaniment", null, layout))
        assertEquals(
            FallbackVocalSelection("accompaniment", null, layout),
            state.onPrepared(),
        )
    }

    @Test
    fun vocalSelectionRequestedAfterPrepareCanBeAppliedImmediately() {
        val state = FallbackVocalSelectionState()
        state.beginPreparation()
        state.onPrepared()

        val layout = AudioLayout(layout = "DUAL_TRACK", accompanimentTrackIndex = 1)
        val requested = FallbackVocalSelection("accompaniment", 1, layout)

        assertEquals(requested, state.select("accompaniment", 1, layout))
    }

    @Test
    fun dualChannelSelectionPreservesChannelMaskAcrossVolumeAndMuteUpdates() {
        val layout = AudioLayout(
            layout = "DUAL_CHANNEL",
            originalChannel = "LEFT",
            accompanimentChannel = "RIGHT",
        )
        val accompaniment = FallbackVocalSelection("accompaniment", null, layout)

        assertEquals(
            FallbackChannelGains(left = 0f, right = 0.6f),
            FallbackAudioMixPolicy.resolveGains(0.6f, muted = false, accompaniment, channelModeOverride = null),
        )
        assertEquals(
            FallbackChannelGains(left = 0f, right = 0f),
            FallbackAudioMixPolicy.resolveGains(0.6f, muted = true, accompaniment, channelModeOverride = null),
        )
        assertEquals(
            FallbackChannelGains(left = 0.4f, right = 0.4f),
            FallbackAudioMixPolicy.resolveGains(
                0.4f,
                muted = false,
                FallbackVocalSelection("accompaniment", 1, AudioLayout("DUAL_TRACK", 1)),
                channelModeOverride = null,
            ),
        )
        assertEquals(
            FallbackChannelGains(left = 0f, right = 0f),
            FallbackAudioMixPolicy.resolveGains(0.5f, muted = true, selection = null, channelModeOverride = "accompaniment"),
        )
    }
}
