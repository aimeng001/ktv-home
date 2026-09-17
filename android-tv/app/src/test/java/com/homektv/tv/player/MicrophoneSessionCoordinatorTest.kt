package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneSessionCoordinatorTest {
    @Test
    fun sessionExceptionResetsRunningStateAllowingRestart() {
        val coordinator = MicrophoneSessionCoordinator()
        val session1 = coordinator.beginSession()
        assertTrue(coordinator.isRunning)
        assertTrue(coordinator.isCurrent(session1))

        // Worker encounters exception and terminates
        coordinator.endSession(session1)
        assertFalse("Running flag must be reset to false on failure", coordinator.isRunning)
        assertFalse(coordinator.isCurrent(session1))

        // Subsequent start can begin immediately
        val session2 = coordinator.beginSession()
        assertEquals(2L, session2)
        assertTrue(coordinator.isRunning)
        assertTrue(coordinator.isCurrent(session2))
    }

    @Test
    fun staleSessionExceptionCannotOverwriteNewerSession() {
        val coordinator = MicrophoneSessionCoordinator()
        val session1 = coordinator.beginSession()

        // Stop and start a new session
        coordinator.stop()
        val session2 = coordinator.beginSession()

        // Old worker tries to report state/error
        assertFalse("Stale session 1 must not be current", coordinator.isCurrent(session1))
        assertTrue("New session 2 must be current", coordinator.isCurrent(session2))

        // Stale session calls endSession
        coordinator.endSession(session1)
        assertTrue("New session 2 running state must not be clobbered by session 1", coordinator.isRunning)
    }
}
