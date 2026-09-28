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
}
