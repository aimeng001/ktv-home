package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9PinyinSearchPolicyTest {

    @Test
    fun testDigitToLettersMapping() {
        assertEquals("ABC", T9PinyinSearchPolicy.getLettersForDigit('2'))
        assertEquals("DEF", T9PinyinSearchPolicy.getLettersForDigit('3'))
        assertEquals("GHI", T9PinyinSearchPolicy.getLettersForDigit('4'))
        assertEquals("JKL", T9PinyinSearchPolicy.getLettersForDigit('5'))
        assertEquals("MNO", T9PinyinSearchPolicy.getLettersForDigit('6'))
        assertEquals("PQRS", T9PinyinSearchPolicy.getLettersForDigit('7'))
        assertEquals("TUV", T9PinyinSearchPolicy.getLettersForDigit('8'))
        assertEquals("WXYZ", T9PinyinSearchPolicy.getLettersForDigit('9'))
        assertEquals("", T9PinyinSearchPolicy.getLettersForDigit('1'))
        assertEquals("", T9PinyinSearchPolicy.getLettersForDigit('0'))
    }

    @Test
    fun testRegexPatternBuilding() {
        val pattern = T9PinyinSearchPolicy.buildRegexPattern("955")
        assertTrue("ZJL".matches(pattern.toRegex()))
        assertTrue("WKL".matches(pattern.toRegex()))
        assertTrue("YJL".matches(pattern.toRegex()))
    }

    @Test
    fun testMultiTapCycle() {
        assertEquals('A', T9PinyinSearchPolicy.resolveMultiTap('2', 1))
        assertEquals('B', T9PinyinSearchPolicy.resolveMultiTap('2', 2))
        assertEquals('C', T9PinyinSearchPolicy.resolveMultiTap('2', 3))
        assertEquals('A', T9PinyinSearchPolicy.resolveMultiTap('2', 4)) // wraps around

        assertEquals('P', T9PinyinSearchPolicy.resolveMultiTap('7', 1))
        assertEquals('Q', T9PinyinSearchPolicy.resolveMultiTap('7', 2))
        assertEquals('R', T9PinyinSearchPolicy.resolveMultiTap('7', 3))
        assertEquals('S', T9PinyinSearchPolicy.resolveMultiTap('7', 4))
        assertEquals('P', T9PinyinSearchPolicy.resolveMultiTap('7', 5)) // wraps around
    }
}
