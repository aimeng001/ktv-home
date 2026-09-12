package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvBottomBarStateTest {

    @Test
    fun standardStereo_disablesVocalToggle() {
        val policy = VocalTogglePolicy.resolve("NORMAL_STEREO", 1)
        assertFalse(policy.isEnabled)
        assertEquals("标准立体声(不可消音)", policy.hint)
    }

    @Test
    fun dualTrack_enablesVocalToggle() {
        val policy = VocalTogglePolicy.resolve("DUAL_TRACK", 2)
        assertTrue(policy.isEnabled)
        assertEquals("原唱/伴唱", policy.hint)
    }

    @Test
    fun dualChannel_enablesVocalToggle() {
        val policy = VocalTogglePolicy.resolve("DUAL_CHANNEL", 1)
        assertTrue(policy.isEnabled)
        assertEquals("原唱/伴唱", policy.hint)
    }

    @Test
    fun nullLayoutSingleTrack_disablesVocalToggle() {
        val policy = VocalTogglePolicy.resolve(null, 1)
        assertFalse(policy.isEnabled)
    }

    @Test
    fun multiTrack_autoEnablesVocalToggle() {
        val policy = VocalTogglePolicy.resolve(null, 2)
        assertTrue(policy.isEnabled)
    }
}
