package com.homektv.tv.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCoordinatorTest {
    @Test
    fun sameResourceIsRejectedButDifferentResourcesCanRunInParallel() {
        val coordinator = ActionCoordinator()
        val firstSong = ActionKey("order", 101L)
        val secondSong = ActionKey("order", 102L)

        assertTrue(coordinator.tryStart(firstSong))
        assertFalse(coordinator.tryStart(firstSong))
        assertTrue(coordinator.tryStart(secondSong))
        coordinator.finish(firstSong)
        assertTrue(coordinator.tryStart(firstSong))
    }

    @Test
    fun songQueueMutationKeyRejectsSimultaneousOrderAndOrderTopForTheSameSong() {
        val coordinator = ActionCoordinator()
        val songId = 200L
        val orderAction = ActionKey("song_queue_mutation", songId)
        val orderTopAction = ActionKey("song_queue_mutation", songId)

        assertTrue(coordinator.tryStart(orderAction))
        assertFalse("Concurrent order top for the same song must be rejected", coordinator.tryStart(orderTopAction))
        coordinator.finish(orderAction)
        assertTrue(coordinator.tryStart(orderTopAction))
    }
}
