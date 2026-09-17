package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKeyboardMultiTapPolicyTest {

    @Test
    fun testFirstTapReturnsFirstLetterWithoutReplace() {
        val policy = KtvKeyboardMultiTapPolicy(multiTapTimeoutMs = 800L)
        val res = policy.onDigitPressed('2', currentTimeMs = 1000L)

        assertEquals('A', res?.letter)
        assertFalse(res?.isReplace ?: true)
    }

    @Test
    fun testSecondTapWithinTimeoutReplacesWithNextLetter() {
        val policy = KtvKeyboardMultiTapPolicy(multiTapTimeoutMs = 800L)
        policy.onDigitPressed('2', currentTimeMs = 1000L)
        val res2 = policy.onDigitPressed('2', currentTimeMs = 1400L)

        assertEquals('B', res2?.letter)
        assertTrue(res2?.isReplace ?: false)

        val res3 = policy.onDigitPressed('2', currentTimeMs = 1600L)
        assertEquals('C', res3?.letter)
        assertTrue(res3?.isReplace ?: false)
    }

    @Test
    fun testTapAfterTimeoutAppendsNewLetter() {
        val policy = KtvKeyboardMultiTapPolicy(multiTapTimeoutMs = 800L)
        policy.onDigitPressed('2', currentTimeMs = 1000L)
        val res2 = policy.onDigitPressed('2', currentTimeMs = 2000L) // >800ms later

        assertEquals('A', res2?.letter)
        assertFalse(res2?.isReplace ?: true)
    }

    @Test
    fun testDifferentDigitAppendsImmediately() {
        val policy = KtvKeyboardMultiTapPolicy(multiTapTimeoutMs = 800L)
        policy.onDigitPressed('9', currentTimeMs = 1000L) // 'W'
        val res2 = policy.onDigitPressed('5', currentTimeMs = 1200L) // 'J'

        assertEquals('J', res2?.letter)
        assertFalse(res2?.isReplace ?: true)
    }
}
