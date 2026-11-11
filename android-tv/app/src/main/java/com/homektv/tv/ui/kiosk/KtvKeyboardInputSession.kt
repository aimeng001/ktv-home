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
    private var _pendingBopomofo = ""

    var mode: KeyboardLayoutMode = KeyboardLayoutMode.QWERTY
        private set

    val currentKeyword: String get() = state.currentKeyword
    val pendingBopomofo: String get() = _pendingBopomofo

    fun onDigitPressed(digit: Char, currentTimeMs: Long = System.currentTimeMillis()): String {
        _pendingBopomofo = ""
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
        _pendingBopomofo = ""
        state.input(letter.toString())
        return state.currentKeyword
    }

    /** Number-panel input appends a literal digit while physical remote keys retain legacy T9. */
    fun onNumericKeyPressed(digit: Char): String {
        if (digit !in '0'..'9') return state.currentKeyword
        multiTapPolicy.reset()
        _pendingBopomofo = ""
        state.input(digit.toString())
        return state.currentKeyword
    }

    /**
     * Commit only a verified initial consonant. Other Bopomofo symbols remain visible local
     * composition state and never become guessed search text.
     */
    fun onBopomofoSymbolPressed(symbol: Char): String {
        if (!BopomofoPinyinMapper.isKnown(symbol)) return state.currentKeyword
        multiTapPolicy.reset()
        val initial = BopomofoPinyinMapper.toPinyinInitial(symbol)
        if (initial != null) {
            _pendingBopomofo = ""
            state.input(initial)
        } else if (BopomofoPinyinMapper.isComposable(symbol)) {
            _pendingBopomofo += symbol
        }
        return state.currentKeyword
    }

    fun onCandidateCharacterPressed(character: String): String {
        if (character.length != 1) return state.currentKeyword
        multiTapPolicy.reset()
        _pendingBopomofo = ""
        state.input(character)
        return state.currentKeyword
    }

    fun backspace(): String {
        multiTapPolicy.reset()
        if (_pendingBopomofo.isNotEmpty()) {
            _pendingBopomofo = _pendingBopomofo.dropLast(1)
        } else {
            state.backspace()
        }
        return state.currentKeyword
    }

    fun clear(): String {
        multiTapPolicy.reset()
        _pendingBopomofo = ""
        state.clear()
        return state.currentKeyword
    }

    fun clearPendingBopomofo() {
        multiTapPolicy.reset()
        _pendingBopomofo = ""
    }

    fun toggleMode(): KeyboardLayoutMode {
        multiTapPolicy.reset()
        _pendingBopomofo = ""
        mode = if (mode == KeyboardLayoutMode.T9) KeyboardLayoutMode.QWERTY else KeyboardLayoutMode.T9
        return mode
    }

    fun setKeyword(keyword: String): String {
        multiTapPolicy.reset()
        _pendingBopomofo = ""
        state.clear()
        keyword.forEach { state.input(it.toString()) }
        return state.currentKeyword
    }
}
