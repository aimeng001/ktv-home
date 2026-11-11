package com.homektv.tv.ui.kiosk

/**
 * 商用点歌机 T9 九宫格拼音首字母映射策略。
 */
object T9PinyinSearchPolicy {

    const val SEARCH_DEBOUNCE_MS = 150L

    /** Normalizes input while preserving the existing catalog's pinyin-search semantics. */
    fun normalizeSearchKeyword(keyword: String): String = keyword.trim().uppercase()

    fun isAcronymQuery(keyword: String): Boolean {
        val normalized = normalizeSearchKeyword(keyword)
        return normalized.isNotEmpty() && normalized.all { it in 'A'..'Z' }
    }

    fun remainingDebounceMs(elapsedSinceInputMs: Long): Long =
        (SEARCH_DEBOUNCE_MS - elapsedSinceInputMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    private val DIGIT_MAP = mapOf(
        '2' to "ABC",
        '3' to "DEF",
        '4' to "GHI",
        '5' to "JKL",
        '6' to "MNO",
        '7' to "PQRS",
        '8' to "TUV",
        '9' to "WXYZ",
    )

    fun getLettersForDigit(digit: Char): String = DIGIT_MAP[digit] ?: ""

    fun buildRegexPattern(digits: String): String {
        val sb = StringBuilder("^")
        for (ch in digits) {
            val letters = DIGIT_MAP[ch]
            if (!letters.isNullOrEmpty()) {
                sb.append("[$letters]")
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun resolveMultiTap(digit: Char, tapCount: Int): Char? {
        val letters = DIGIT_MAP[digit] ?: return null
        if (letters.isEmpty() || tapCount <= 0) return null
        val idx = (tapCount - 1) % letters.length
        return letters[idx]
    }
}
