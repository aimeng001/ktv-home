package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import com.homektv.tv.ui.VocalTogglePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class VocalTogglePolicyTest {
    @Test
    fun togglingFromOriginalSwitchesToAccompanimentMode() {
        val policy = VocalTogglePolicy()

        assertEquals(VocalMode.ACCOMPANIMENT, policy.getNextVocalMode(VocalMode.ORIGINAL))
        assertEquals(VocalMode.ORIGINAL, policy.getNextVocalMode(VocalMode.ACCOMPANIMENT))
    }

    @Test
    fun vocalModeStateFlowPublishesModeChangesSynchronously() {
        val state = VocalModeState()

        assertEquals(VocalMode.ACCOMPANIMENT, state.state.value)
        state.update(VocalMode.ORIGINAL)
        assertEquals(VocalMode.ORIGINAL, state.state.value)
        state.update("accompaniment")
        assertEquals(VocalMode.ACCOMPANIMENT, state.state.value)
    }

    @Test
    fun dualTrackPlanSelectsTheRequestedTrackWithoutAPlaybackReplacementAction() {
        val layout = AudioLayout(
            layout = "DUAL_TRACK",
            originalTrackIndex = 0,
            accompanimentTrackIndex = 1,
        )

        assertEquals(
            VocalSelectionAction.SelectTrack(0),
            resolveVocalSelectionAction(VocalMode.ORIGINAL, layout, 2, legacyAccompanimentIndex = 1),
        )
        assertEquals(
            VocalSelectionAction.SelectTrack(1),
            resolveVocalSelectionAction(VocalMode.ACCOMPANIMENT, layout, 2, legacyAccompanimentIndex = 1),
        )
    }

    @Test
    fun dualChannelOriginalPassesStereoAndAccompanimentDuplicatesItsDeclaredChannel() {
        val leftAccompaniment = AudioLayout(
            layout = "DUAL_CHANNEL",
            originalChannel = "RIGHT",
            accompanimentChannel = "LEFT",
        )

        assertEquals(
            VocalSelectionAction.MapChannels(PcmChannelMode.STEREO),
            resolveVocalSelectionAction(VocalMode.ORIGINAL, leftAccompaniment, 1, null),
        )
        assertEquals(
            VocalSelectionAction.MapChannels(PcmChannelMode.LEFT_MONO),
            resolveVocalSelectionAction(VocalMode.ACCOMPANIMENT, leftAccompaniment, 1, null),
        )
    }

    @Test
    fun initialChannelMatrixMatchesRequestedModeBeforePlaybackStarts() {
        val layout = AudioLayout(
            layout = "DUAL_CHANNEL",
            originalChannel = "RIGHT",
            accompanimentChannel = "LEFT",
        )

        assertEquals(
            PcmChannelMode.STEREO,
            channelMixingModeForPlayback(VocalMode.ORIGINAL, layout, audioTrackCount = 1),
        )
        assertEquals(
            PcmChannelMode.LEFT_MONO,
            channelMixingModeForPlayback(VocalMode.ACCOMPANIMENT, layout, audioTrackCount = 1),
        )
        assertEquals(
            PcmChannelMode.STEREO,
            channelMixingModeForPlayback(
                VocalMode.ACCOMPANIMENT,
                AudioLayout.normalStereo(),
                audioTrackCount = 1,
            ),
        )
    }

    @Test
    fun leftAccompanimentMatrixFeedsBothOutputsAndMutesRightVocalInput() {
        val matrix = channelMixingMatrix(PcmChannelMode.LEFT_MONO)

        assertEquals(1f, matrix.getMixingCoefficient(0, 0), 0f)
        assertEquals(1f, matrix.getMixingCoefficient(0, 1), 0f)
        assertEquals(0f, matrix.getMixingCoefficient(1, 0), 0f)
        assertEquals(0f, matrix.getMixingCoefficient(1, 1), 0f)
    }

    @Test
    fun originalStereoMatrixDoesNotCollapseEitherChannel() {
        val matrix = channelMixingMatrix(PcmChannelMode.STEREO)

        assertEquals(1f, matrix.getMixingCoefficient(0, 0), 0f)
        assertEquals(0f, matrix.getMixingCoefficient(0, 1), 0f)
        assertEquals(0f, matrix.getMixingCoefficient(1, 0), 0f)
        assertEquals(1f, matrix.getMixingCoefficient(1, 1), 0f)
    }

    @Test
    fun applyingTrackSelectionActionLeavesTheCurrentPositionUntouched() {
        val sink = PositionTrackingSink(positionMs = 178_456L)
        val action = resolveVocalSelectionAction(
            VocalMode.ACCOMPANIMENT,
            AudioLayout(
                layout = "DUAL_TRACK",
                originalTrackIndex = 0,
                accompanimentTrackIndex = 1,
            ),
            audioTrackCount = 2,
            legacyAccompanimentIndex = 1,
        )

        applyVocalSelectionAction(action, sink)

        assertEquals(1, sink.selectedTrack)
        assertEquals(178_456L, sink.positionMs)
    }

    private class PositionTrackingSink(var positionMs: Long) : VocalSelectionSink {
        var selectedTrack: Int? = null
        var channelMode: PcmChannelMode? = null

        override fun selectTrack(index: Int) {
            selectedTrack = index
        }

        override fun mapChannels(mode: PcmChannelMode) {
            channelMode = mode
        }
    }
}
