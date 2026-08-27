package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLayoutCapabilitiesTest {
    @Test
    fun dualChannelSupportsVocalSwitch() {
        assertTrue(supportsVocalSwitch(AudioLayout(layout = "DUAL_CHANNEL")))
    }

    @Test
    fun dualTrackSupportsVocalSwitch() {
        assertTrue(supportsVocalSwitch(AudioLayout(layout = "DUAL_TRACK")))
    }

    @Test
    fun normalStereoDoesNotSupportVocalSwitch() {
        assertFalse(supportsVocalSwitch(AudioLayout.normalStereo()))
    }

    @Test
    fun missingLayoutDoesNotClaimVocalSwitchSupport() {
        assertFalse(supportsVocalSwitch(null))
    }
}
