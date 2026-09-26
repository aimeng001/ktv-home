package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPipDisplayPolicyTest {

    @Test
    fun externalDisplayHidesPipAndExplainsWhereVideoIsPlaying() {
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = true,
            playbackState = "playing",
            hasPlaying = true,
        )

        assertFalse(state.videoVisible)
        assertEquals("视频正在外接屏播放", state.label)
    }

    @Test
    fun localDisplayKeepsPipAndUsesPlaybackLabel() {
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = false,
            playbackState = "playing",
            hasPlaying = true,
        )

        assertTrue(state.videoVisible)
        assertEquals("正在播放", state.label)
    }

    @Test
    fun pausedLocalPlaybackKeepsLastVideoFrameVisible() {
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = false,
            playbackState = "paused",
            hasPlaying = true,
        )

        assertTrue(state.videoVisible)
        assertEquals("已暂停", state.label)
    }

    @Test
    fun localIdleWithoutSongHidesEmptyPip() {
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = false,
            playbackState = "idle",
            hasPlaying = false,
        )

        assertFalse(state.videoVisible)
        assertEquals("待机中", state.label)
    }

    @Test
    fun idleSnapshotHidesPipEvenIfItContainsStalePlayingEntry() {
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = false,
            playbackState = "idle",
            hasPlaying = true,
        )

        assertFalse(state.videoVisible)
        assertEquals("待机中", state.label)
    }
}
