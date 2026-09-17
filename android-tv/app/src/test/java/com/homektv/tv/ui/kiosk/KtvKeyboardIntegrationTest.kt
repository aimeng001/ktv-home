package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.KtvKeyboardState
import org.junit.Assert.assertEquals
import org.junit.Test

class KtvKeyboardIntegrationTest {

    @Test
    fun testMultiTapInputSequenceProducesCorrectAcronym() {
        val state = KtvKeyboardState()
        val multiTap = KtvKeyboardMultiTapPolicy()

        // Press '9' (WXYZ) once -> 'W'
        var res = multiTap.onDigitPressed('9', 1000L)!!
        state.input(res.letter.toString())
        assertEquals("W", state.currentKeyword)

        // Press '5' (JKL) once -> 'J'
        res = multiTap.onDigitPressed('5', 1200L)!!
        state.input(res.letter.toString())
        assertEquals("WJ", state.currentKeyword)

        // Press '5' again within 300ms -> should replace 'J' with 'K'
        res = multiTap.onDigitPressed('5', 1400L)!!
        if (res.isReplace) state.backspace()
        state.input(res.letter.toString())
        assertEquals("WK", state.currentKeyword)
    }

    @Test
    fun testKeyboardInputSession_handlesT9AndQwertyModeSwitch() {
        val session = KtvKeyboardInputSession()
        assertEquals(KeyboardLayoutMode.T9, session.mode)

        session.onDigitPressed('2', 1000L) // 'A'
        session.onDigitPressed('2', 1200L) // 'B'
        assertEquals("B", session.currentKeyword)

        session.toggleMode()
        assertEquals(KeyboardLayoutMode.QWERTY, session.mode)

        session.onLetterPressed('C')
        assertEquals("BC", session.currentKeyword)

        session.backspace()
        assertEquals("B", session.currentKeyword)

        session.clear()
        assertEquals("", session.currentKeyword)
    }

    @Test
    fun testKeyboardInputSession_supportsDigitsZeroAndOne() {
        val session = KtvKeyboardInputSession()
        session.onDigitPressed('1', 1000L)
        session.onDigitPressed('0', 1200L)
        assertEquals("10", session.currentKeyword)
    }
}
