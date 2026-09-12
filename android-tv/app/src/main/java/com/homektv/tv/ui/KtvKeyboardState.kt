package com.homektv.tv.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 拼音首字母/字母快捷点歌软键盘状态机。
 *
 * State machine for the on-screen KTV pinyin acronym keyboard.
 */
class KtvKeyboardState(
    private val maxLength: Int = MAX_KEYWORD_LENGTH,
) {
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    val currentKeyword: String get() = _keyword.value

    fun input(char: String) {
        val trimmed = char.trim().uppercase()
        if (trimmed.isEmpty()) return
        if (_keyword.value.length < maxLength) {
            _keyword.value += trimmed
        }
    }

    fun backspace() {
        if (_keyword.value.isNotEmpty()) {
            _keyword.value = _keyword.value.dropLast(1)
        }
    }

    fun clear() {
        _keyword.value = ""
    }

    companion object {
        const val MAX_KEYWORD_LENGTH = 16
    }
}
