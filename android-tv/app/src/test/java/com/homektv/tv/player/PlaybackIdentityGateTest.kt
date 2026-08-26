package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdentityGateTest {
    @Test
    fun requestedIdentityIsNotActiveBeforeMediaReady() {
        val gate = PlaybackIdentityGate()
        val request = identity(queueId = 10L, fileId = 20L, mediaId = "20")

        gate.begin(request)

        assertNull(gate.callbackIdentity())
        assertNull(gate.activeIdentity())
    }

    @Test
    fun replacementSuppressesOldCallbacksUntilNewMediaIsReady() {
        val gate = PlaybackIdentityGate()
        val first = identity(queueId = 10L, fileId = 20L, mediaId = "20")
        val second = identity(queueId = 11L, fileId = 21L, mediaId = "21")

        gate.begin(first)
        assertTrue(gate.markReady("20"))
        gate.begin(second)

        assertNull(gate.callbackIdentity())
        assertTrue(gate.markReady("21"))
        assertEquals(second, gate.callbackIdentity())
    }

    @Test
    fun wrongMediaDoesNotActivatePendingIdentity() {
        val gate = PlaybackIdentityGate()
        val request = identity(queueId = 10L, fileId = 20L, mediaId = "20")

        gate.begin(request)

        assertFalse(gate.markReady("another-media"))
        assertNull(gate.activeIdentity())
    }

    @Test
    fun finishedIdentityIsConsumedOnlyOnce() {
        val gate = PlaybackIdentityGate()
        val request = identity(queueId = 10L, fileId = 20L, mediaId = "20")
        gate.begin(request)
        assertTrue(gate.markReady("20"))

        assertEquals(request, gate.consumeFinishedIdentity())
        assertNull(gate.consumeFinishedIdentity())
    }

    @Test
    fun invalidationRemovesPendingAndActiveIdentities() {
        val gate = PlaybackIdentityGate()
        val request = identity(queueId = 10L, fileId = 20L, mediaId = "20")
        gate.begin(request)
        assertTrue(gate.markReady("20"))

        gate.invalidate()

        assertNull(gate.activeIdentity())
        assertNull(gate.callbackIdentity())
    }

    private fun identity(queueId: Long, fileId: Long, mediaId: String) =
        PlaybackIdentity(queueId, fileId, mediaId)
}
