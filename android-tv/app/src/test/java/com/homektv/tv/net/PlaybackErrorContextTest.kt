package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorContextTest {
    @Test
    fun playbackFailureCarriesTheQueueAndFileThatStartedPlayback() {
        val context = PlaybackErrorContext.forPlayback(7L, 11L)

        assertEquals(7L, context.queueId)
        assertEquals(11L, context.fileId)
    }

    @Test
    fun missingSourceNeverCarriesAFileIdentity() {
        val context = PlaybackErrorContext.missingSource(42L)

        assertEquals(42L, context.queueId)
        assertNull(context.fileId)
    }
}
