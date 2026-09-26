package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.KtvKeyboardState

enum class KeyboardLayoutMode {
    T9,
    QWERTY,
}

/**
 * 电视与触屏软键盘输入会话协调器。
 *
 * Coordinates T9 multi-tap cycling, 26-letter direct input,
 * mode toggling, and input state dispatch.
 */
class KtvKeyboardInputSession(
    val state: KtvKeyboardState = KtvKeyboardState(),
    val multiTapPolicy: KtvKeyboardMultiTapPolicy = KtvKeyboardMultiTapPolicy(),
) {
    var mode: KeyboardLayoutMode = KeyboardLayoutMode.QWERTY
        private set

    val currentKeyword: String get() = state.currentKeyword

    fun onDigitPressed(digit: Char, currentTimeMs: Long = System.currentTimeMillis()): String {
        if (digit in '2'..'9') {
            val res = multiTapPolicy.onDigitPressed(digit, currentTimeMs) ?: return state.currentKeyword
            if (res.isReplace) {
                state.backspace()
            }
            state.input(res.letter.toString())
            return state.currentKeyword
        } else if (digit == '0' || digit == '1') {
            multiTapPolicy.reset()
            state.input(digit.toString())
            return state.currentKeyword
        }
        return state.currentKeyword
    }

    fun onLetterPressed(letter: Char): String {
        multiTapPolicy.reset()
        state.input(letter.toString())
        return state.currentKeyword
    }

    fun backspace(): String {
        multiTapPolicy.reset()
        state.backspace()
        return state.currentKeyword
    }

    fun clear(): String {
        multiTapPolicy.reset()
        state.clear()
        return state.currentKeyword
    }

    fun toggleMode(): KeyboardLayoutMode {
        multiTapPolicy.reset()
        mode = if (mode == KeyboardLayoutMode.T9) KeyboardLayoutMode.QWERTY else KeyboardLayoutMode.T9
        return mode
    }

    fun setKeyword(keyword: String): String {
        multiTapPolicy.reset()
        state.clear()
        keyword.forEach { state.input(it.toString()) }
        return state.currentKeyword
    }
}
