package com.homektv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPlaybackIntentTest {
    @Test
    fun pauseBeforeAsyncPrepareCompletesMustNotStartPlayback() {
        val intent = FallbackPlaybackIntent()
        val attempt = intent.begin(playWhenReady = true)

        assertNull("play intent changes before prepare should be deferred", intent.setPlayWhenReady(false))
        assertFalse("onPrepared must honor the latest paused state", intent.onPrepared(attempt)!!)
    }

    @Test
    fun resumeBeforeAsyncPrepareCompletesMustStartPlayback() {
        val intent = FallbackPlaybackIntent()
        val attempt = intent.begin(playWhenReady = false)

        assertNull("resume before prepare must be deferred", intent.setPlayWhenReady(true))
        assertTrue("onPrepared must honor the latest playing state", intent.onPrepared(attempt)!!)
    }

    @Test
    fun pauseAndResumeAfterPrepareReturnTheRequiredPlayerAction() {
        val intent = FallbackPlaybackIntent()
        val attempt = intent.begin(playWhenReady = true)
        assertTrue(intent.onPrepared(attempt)!!)

        assertEquals("prepared player should pause", false, intent.setPlayWhenReady(false))
        assertEquals("prepared player should resume", true, intent.setPlayWhenReady(true))
    }

    @Test
    fun latePrepareCallbackFromReplacedSongMustBeIgnored() {
        val intent = FallbackPlaybackIntent()
        val staleAttempt = intent.begin(playWhenReady = true)
        val activeAttempt = intent.begin(playWhenReady = false)

        assertNull("replaced request cannot prepare the active player", intent.onPrepared(staleAttempt))
        assertEquals(false, intent.onPrepared(activeAttempt))
        assertTrue(intent.isCurrent(activeAttempt))
    }
}
