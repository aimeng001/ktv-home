package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskPlaylistOrderPolicyTest {
    @Test
    fun pendingPlaylistOrderIsDisabledUntilStateClears() {
        val pending = KtvKioskPlaylistOrderPolicy.resolve(isPending = true)

        assertEquals("整单点歌中…", pending.text)
        assertFalse(pending.isEnabled)
    }

    @Test
    fun idlePlaylistOrderRemainsEnabled() {
        val idle = KtvKioskPlaylistOrderPolicy.resolve(isPending = false)

        assertEquals("整单点歌", idle.text)
        assertTrue(idle.isEnabled)
    }
}
