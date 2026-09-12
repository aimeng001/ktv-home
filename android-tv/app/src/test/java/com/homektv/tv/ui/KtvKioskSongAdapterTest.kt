package com.homektv.tv.ui

import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskSongAdapterTest {

    @Test
    fun diffCallback_identifiesSameSongById() {
        val s1 = SongDto(id = 100L, title = "海阔天空", artist = "Beyond")
        val s2 = SongDto(id = 100L, title = "海阔天空 (Live)", artist = "Beyond")
        val s3 = SongDto(id = 101L, title = "光辉岁月", artist = "Beyond")

        assertTrue(KtvKioskSongAdapter.DIFF_CALLBACK.areItemsTheSame(s1, s2))
        assertFalse(KtvKioskSongAdapter.DIFF_CALLBACK.areItemsTheSame(s1, s3))
        assertFalse(KtvKioskSongAdapter.DIFF_CALLBACK.areContentsTheSame(s1, s2))
        assertTrue(KtvKioskSongAdapter.DIFF_CALLBACK.areContentsTheSame(s1, s1))
    }

    @Test
    fun buttonPolicy_onSubmit_disablesButtonAndShowsSubmittingText() {
        val orderState = KtvKioskSongAdapter.SongOrderButtonPolicy.onSubmit(isTop = false)
        assertEquals("点歌中...", orderState.text)
        assertFalse(orderState.isEnabled)

        val topState = KtvKioskSongAdapter.SongOrderButtonPolicy.onSubmit(isTop = true)
        assertEquals("插播中...", topState.text)
        assertFalse(topState.isEnabled)
    }

    @Test
    fun buttonPolicy_onComplete_whenSuccess_showsFinishedTextAndKeepsDisabled() {
        val orderSuccess = KtvKioskSongAdapter.SongOrderButtonPolicy.onComplete(isTop = false, success = true)
        assertEquals("已点", orderSuccess.text)
        assertFalse(orderSuccess.isEnabled)

        val topSuccess = KtvKioskSongAdapter.SongOrderButtonPolicy.onComplete(isTop = true, success = true)
        assertEquals("已置顶", topSuccess.text)
        assertFalse(topSuccess.isEnabled)
    }

    @Test
    fun buttonPolicy_onComplete_whenFailed_restoresInitialTextAndReEnablesButton() {
        // F-07: Rollback button state on network failure or business rejection
        val orderFailed = KtvKioskSongAdapter.SongOrderButtonPolicy.onComplete(isTop = false, success = false)
        assertEquals("点歌", orderFailed.text)
        assertTrue(orderFailed.isEnabled)

        val topFailed = KtvKioskSongAdapter.SongOrderButtonPolicy.onComplete(isTop = true, success = false)
        assertEquals("优先", topFailed.text)
        assertTrue(topFailed.isEnabled)
    }

    @Test
    fun buttonPolicy_shouldApplyResult_returnsTrueOnlyWhenTagMatchesCurrentSong() {
        val currentSongId = 88L
        assertTrue(KtvKioskSongAdapter.SongOrderButtonPolicy.shouldApplyResult(currentSongId, 88L))
        assertFalse(KtvKioskSongAdapter.SongOrderButtonPolicy.shouldApplyResult(currentSongId, 99L))
        assertFalse(KtvKioskSongAdapter.SongOrderButtonPolicy.shouldApplyResult(null, 88L))
    }

    @Test
    fun buttonPolicy_resolveStateOnBind_distinguishesInFlightAndIdle() {
        val submittingState = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = true,
        )
        assertEquals("点歌中...", submittingState.text)
        assertFalse(submittingState.isEnabled)

        val idleState = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
        )
        assertEquals("点歌", idleState.text)
        assertTrue(idleState.isEnabled)
    }

    @Test
    fun buttonPolicy_resolveStateOnBind_retainsPlayingAndWaitingStatesAcrossRecycle() {
        // Playing song
        val orderPlaying = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
            queueState = SongQueueState.Playing,
        )
        assertEquals("演唱中", orderPlaying.text)
        assertFalse(orderPlaying.isEnabled)

        val topPlaying = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = true,
            isSubmitting = false,
            queueState = SongQueueState.Playing,
        )
        assertEquals("优先", topPlaying.text)
        assertFalse(topPlaying.isEnabled)

        // Waiting song
        val orderWaiting = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
            queueState = SongQueueState.Waiting(3),
        )
        assertEquals("已点", orderWaiting.text)
        assertFalse(orderWaiting.isEnabled)

        val topWaiting = KtvKioskSongAdapter.SongOrderButtonPolicy.resolveStateOnBind(
            isTop = true,
            isSubmitting = false,
            queueState = SongQueueState.Waiting(3),
        )
        assertEquals("优先", topWaiting.text)
        assertTrue(topWaiting.isEnabled)
    }

    @Test
    fun queueProjectionPolicy_shouldNotifyChange_onlyWhenCountGreaterThanZero() {
        assertFalse(KtvKioskSongAdapter.QueueProjectionPolicy.shouldNotifyChange(0))
        assertFalse(KtvKioskSongAdapter.QueueProjectionPolicy.shouldNotifyChange(-1))
        assertTrue(KtvKioskSongAdapter.QueueProjectionPolicy.shouldNotifyChange(1))
        assertTrue(KtvKioskSongAdapter.QueueProjectionPolicy.shouldNotifyChange(50))
    }
}

