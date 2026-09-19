package com.homektv.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BackExitGateTest {

    @Test
    fun testFirstBackAtTopLevelPromptsUser() {
        val gate = BackExitGate(timeoutMs = 2000L)
        val decision = gate.onBack(nowMs = 1000L, isAtTopLevel = true)
        assertEquals(BackExitDecision.CONSUMED_AND_PROMPTED, decision)
    }

    @Test
    fun testSecondBackWithinTimeoutExitsApp() {
        val gate = BackExitGate(timeoutMs = 2000L)
        gate.onBack(nowMs = 1000L, isAtTopLevel = true)
        val decision = gate.onBack(nowMs = 2500L, isAtTopLevel = true) // 1.5s later <= 2s
        assertEquals(BackExitDecision.EXIT_APP, decision)
    }

    @Test
    fun testSecondBackAfterTimeoutPromptsAgain() {
        val gate = BackExitGate(timeoutMs = 2000L)
        gate.onBack(nowMs = 1000L, isAtTopLevel = true)
        val decision = gate.onBack(nowMs = 3500L, isAtTopLevel = true) // 2.5s later > 2s
        assertEquals(BackExitDecision.CONSUMED_AND_PROMPTED, decision)
    }

    @Test
    fun testBackWhenNotAtTopLevelIsIgnoredAndResetsGate() {
        val gate = BackExitGate(timeoutMs = 2000L)
        gate.onBack(nowMs = 1000L, isAtTopLevel = true)

        // Sub-page handles back
        val decision = gate.onBack(nowMs = 1500L, isAtTopLevel = false)
        assertEquals(BackExitDecision.IGNORED, decision)

        // Next top-level back should prompt again, not exit
        val nextDecision = gate.onBack(nowMs = 2000L, isAtTopLevel = true)
        assertEquals(BackExitDecision.CONSUMED_AND_PROMPTED, nextDecision)
    }

    @Test
    fun testUserInteractionInterruptsExitSequence() {
        val gate = BackExitGate(timeoutMs = 2000L)
        gate.onBack(nowMs = 1000L, isAtTopLevel = true)

        // User navigated focus or selected a song or switched tab
        gate.reset()

        // Subsequent back must prompt again rather than accidentally exit
        val decision = gate.onBack(nowMs = 1800L, isAtTopLevel = true)
        assertEquals(BackExitDecision.CONSUMED_AND_PROMPTED, decision)
    }
}
