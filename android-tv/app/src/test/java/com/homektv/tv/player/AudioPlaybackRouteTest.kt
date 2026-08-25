package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackRouteTest {
    @Test
    fun dualTrackUsesExistingTrackSelectionRoute() {
        assertEquals(AudioPlaybackRoute.TRACK_SELECTION, AudioPlaybackRoute.forLayout("DUAL_TRACK"))
    }

    @Test
    fun dualChannelUsesPcmChannelRoute() {
        assertEquals(AudioPlaybackRoute.PCM_CHANNEL_MAPPING, AudioPlaybackRoute.forLayout("DUAL_CHANNEL"))
    }

    @Test
    fun normalStereoUsesPassthroughRoute() {
        assertEquals(AudioPlaybackRoute.PASSTHROUGH, AudioPlaybackRoute.forLayout("NORMAL_STEREO"))
    }
}
