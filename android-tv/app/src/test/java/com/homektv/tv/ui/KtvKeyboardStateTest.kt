package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvKeyboardStateTest {

    @Test
    fun appendLetter_accumulatesUppercase() {
        val state = KtvKeyboardState()
        state.input("z")
        state.input("j")
        state.input("l")
        assertEquals("ZJL", state.currentKeyword)
    }

    @Test
    fun backspace_removesLastLetter() {
        val state = KtvKeyboardState()
        state.input("Q")
        state.input("T")
        state.backspace()
        assertEquals("Q", state.currentKeyword)
    }

    @Test
    fun backspace_onEmpty_remainsEmpty() {
        val state = KtvKeyboardState()
        state.backspace()
        assertEquals("", state.currentKeyword)
    }

    @Test
    fun clear_emptiesKeyword() {
        val state = KtvKeyboardState()
        state.input("D")
        state.input("R")
        state.clear()
        assertEquals("", state.currentKeyword)
    }

    @Test
    fun input_enforcesBoundedLength() {
        val state = KtvKeyboardState()
        repeat(30) { state.input("A") }
        assertEquals(16, state.currentKeyword.length)
    }
}
