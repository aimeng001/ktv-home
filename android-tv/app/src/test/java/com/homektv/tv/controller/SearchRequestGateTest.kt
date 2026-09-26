package com.homektv.tv.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestGateTest {
    @Test
    fun anOlderRequestCannotWinWhenTheSameQueryIsEnteredAgain() {
        val gate = SearchRequestGate()
        val firstA = gate.begin("A")
        gate.begin("B")
        val secondA = gate.begin("A")

        assertFalse(gate.isCurrent(firstA))
        assertTrue(gate.isCurrent(secondA))
    }

    @Test
    fun clearingTheQueryInvalidatesAnInFlightResponse() {
        val gate = SearchRequestGate()
        val search = gate.begin("粤语")
        val clear = gate.begin("")

        assertFalse(gate.isCurrent(search))
        assertTrue(gate.isCurrent(clear))
    }
}
