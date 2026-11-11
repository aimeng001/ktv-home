package com.homektv.tv.ui.kiosk

/**
 * Bopomofo symbols accepted by the local keyboard. Only the 21 initial consonants map to
 * pinyin initials; medials, finals, and tone marks remain local composition state.
 */
object BopomofoPinyinMapper {
    private val initials = mapOf(
        'ㄅ' to "B", 'ㄆ' to "P", 'ㄇ' to "M", 'ㄈ' to "F", 'ㄉ' to "D",
        'ㄊ' to "T", 'ㄋ' to "N", 'ㄌ' to "L", 'ㄍ' to "G", 'ㄎ' to "K",
        'ㄏ' to "H", 'ㄐ' to "J", 'ㄑ' to "Q", 'ㄒ' to "X", 'ㄓ' to "Z",
        'ㄔ' to "C", 'ㄕ' to "S", 'ㄖ' to "R", 'ㄗ' to "Z", 'ㄘ' to "C",
        'ㄙ' to "S",
    )

    private val finalsAndMedials = listOf(
        'ㄧ', 'ㄨ', 'ㄩ', 'ㄚ', 'ㄛ', 'ㄜ', 'ㄝ', 'ㄞ',
        'ㄟ', 'ㄠ', 'ㄡ', 'ㄢ', 'ㄣ', 'ㄤ', 'ㄥ', 'ㄦ',
    )

    private val tones = listOf('ˊ', 'ˇ', 'ˋ', '˙')

    /** Stable five-column order grouped by initial consonants, finals, then tones. */
    val keys: List<Char> = initials.keys.toList() + finalsAndMedials + tones

    fun toPinyinInitial(symbol: Char): String? = initials[symbol]

    fun isComposable(symbol: Char): Boolean = symbol in finalsAndMedials || symbol in tones

    fun isKnown(symbol: Char): Boolean = symbol in keys
}
