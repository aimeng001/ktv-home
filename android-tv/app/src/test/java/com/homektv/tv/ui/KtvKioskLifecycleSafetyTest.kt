package com.homektv.tv.ui

import com.homektv.tv.player.EffectSounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskLifecycleSafetyTest {

    @Test
    fun pipLabelPolicy_resolvesIdleWhenNoSongOrIdleState() {
        assertEquals("待机中", PipLabelPolicy.resolve("idle", false))
        assertEquals("待机中", PipLabelPolicy.resolve("idle", true))
        assertEquals("待机中", PipLabelPolicy.resolve("playing", false))
    }

    @Test
    fun pipLabelPolicy_resolvesPausedAndPlaying() {
        assertEquals("已暂停", PipLabelPolicy.resolve("paused", true))
        assertEquals("正在播放", PipLabelPolicy.resolve("playing", true))
    }

    @Test
    fun soundEffectMapping_matchesActionAndLabel() {
        val applauseAction = "clap"
        val cheerAction = "cheer"

        assertTrue(EffectSounds.ids.contains(applauseAction))
        assertTrue(EffectSounds.ids.contains(cheerAction))
        assertNotNull(EffectSounds.find(applauseAction))
        assertNotNull(EffectSounds.find(cheerAction))
        assertFalse(EffectSounds.ids.contains("applause"))
    }
}
