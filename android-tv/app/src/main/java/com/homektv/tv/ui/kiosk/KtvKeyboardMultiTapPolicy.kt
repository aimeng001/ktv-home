package com.homektv.tv.ui.kiosk

/**
 * T9 拼音键盘多击（Multi-tap）按键解析策略。
 */
class KtvKeyboardMultiTapPolicy(
    private val multiTapTimeoutMs: Long = 800L,
) {
    private var lastDigit: Char? = null
    private var lastTapTime: Long = 0L
    private var currentTapCount: Int = 0

    data class MultiTapResult(
        val letter: Char,
        val isReplace: Boolean,
    )

    fun onDigitPressed(digit: Char, currentTimeMs: Long): MultiTapResult? {
        if (digit !in '2'..'9') return null
        return if (digit == lastDigit && (currentTimeMs - lastTapTime) < multiTapTimeoutMs) {
            currentTapCount++
            lastTapTime = currentTimeMs
            val letter = T9PinyinSearchPolicy.resolveMultiTap(digit, currentTapCount) ?: return null
            MultiTapResult(letter, isReplace = true)
        } else {
            lastDigit = digit
            currentTapCount = 1
            lastTapTime = currentTimeMs
            val letter = T9PinyinSearchPolicy.resolveMultiTap(digit, 1) ?: return null
            MultiTapResult(letter, isReplace = false)
        }
    }

    fun reset() {
        lastDigit = null
        currentTapCount = 0
        lastTapTime = 0L
    }
}
