package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryStateTest {
    @Test
    fun pauseCancelsResumeIntentAndSeekUsesLatestPosition() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = true)
        state.pause()

        assertFalse(state.playWhenReady)
        assertEquals(900L, state.positionForRetry(900L))

        state.seekTo(1_500L)
        assertEquals(1_500L, state.positionForRetry(900L))
    }

    @Test
    fun replacementClearsOldPlaybackIntent() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 700L, playWhenReady = true)

        state.beginReplacement()

        assertFalse(state.playWhenReady)
        assertTrue(state.isReplacementActive)
    }

    @Test
    fun reusedMediaCanUpdateTheLatestPlayIntent() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = false)

        state.setPlayWhenReady(true)
        assertTrue(state.playWhenReady)
        state.setPlayWhenReady(false)
        assertFalse(state.playWhenReady)
    }

    /**
     * 重唱（restart → seekTo(0)）之后，进度已经推进到 3:00。
     * 此时若发生瞬时错误，瞬时错误发生时播放器自身的位置可能已不可用（归零），
     * 重试必须回到进度采样的续播点，而不是被"重唱"留下的 0 永久钉死。
     */
    @Test
    fun retryAfterRestartResumesFromTrackedPlaybackPosition() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = true)
        state.seekTo(0L)
        state.onPositionSampled(180_000L)

        assertEquals(180_000L, state.positionForRetry(0L))
    }

    /** 显式跳转后完全没有播放进度可跟踪时，仍应回到跳转目标（保留原有语义）。 */
    @Test
    fun retryWithoutTrackedProgressFallsBackToRequestedPosition() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = true)
        state.seekTo(60_000L)

        assertEquals(60_000L, state.positionForRetry(0L))
    }

    /** 跳转到 1:00 后继续播到 4:00 才失败：续播点应取更靠后的 4:00，而不是跳回 1:00。 */
    @Test
    fun retryPrefersTheFurtherPositionWhenBothAreKnown() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = true)
        state.seekTo(60_000L)

        assertEquals(240_000L, state.positionForRetry(240_000L))
    }

    /** 换歌（beginReplacement）后不得沿用上一首的续播点。 */
    @Test
    fun replacementDropsTrackedPlaybackPosition() {
        val state = PlaybackRetryState()
        state.start(initialPositionMs = 0L, playWhenReady = true)
        state.onPositionSampled(180_000L)

        state.beginReplacement()

        assertEquals(0L, state.positionForRetry(0L))
    }
}
