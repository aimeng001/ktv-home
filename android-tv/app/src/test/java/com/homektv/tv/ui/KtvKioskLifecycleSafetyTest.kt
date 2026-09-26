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
    fun pipLabelPolicy_reportsBufferingWithoutHidingCurrentSong() {
        assertEquals("缓冲中", PipLabelPolicy.resolve("buffering", true))
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

    @Test
    fun kioskOverlayController_destroyReleasesAllDialogsAndCaches() {
        val candidates = sequenceOf(
            java.io.File("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            java.io.File("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
            java.io.File("../app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
        )
        val file = candidates.firstOrNull(java.io.File::isFile)
            ?: error("Cannot locate KtvKioskOverlayController.kt")
        val source = file.readText()
        val destroy = source.substringAfter("fun destroy()").substringBefore("private fun reparentView")

        assertTrue("queueDialog must be dismissed in destroy()", destroy.contains("queueDialog?.dismiss()"))
        assertTrue("queueDialog reference must be cleared", destroy.contains("queueDialog = null"))
        assertTrue("qrDialog must be dismissed in destroy()", destroy.contains("qrDialog?.dismiss()"))
        assertTrue("qrDialog reference must be cleared", destroy.contains("qrDialog = null"))
        assertTrue("cachedQrBitmap reference must be cleared", destroy.contains("cachedQrBitmap = null"))
        assertTrue("singerAvatarCache must be cleared", destroy.contains("singerAvatarCache.clear()"))
        assertTrue("pendingAvatarCallbacks must be cleared", destroy.contains("pendingAvatarCallbacks.clear()"))
        assertTrue("PIP player listener must be removed", destroy.contains("pipObservedPlayer?.removeListener(pipPlaybackListener)"))
        assertTrue("PIP layout listener must be removed", destroy.contains("removeOnLayoutChangeListener(pipLayoutChangeListener)"))
    }
}
