package com.homektv.tv.net

/** Counts UTF-8 bytes without first materializing a second encoded buffer. */
internal object Utf8Budget {
    fun atMost(value: String, maxBytes: Int): Boolean = countAtMost(value, maxBytes) != null

    fun countAtMost(value: String, maxBytes: Int): Int? {
        if (maxBytes < 0) return null

        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val codePointBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            bytes += codePointBytes
            if (bytes > maxBytes) return null
            index += Character.charCount(codePoint)
        }
        return bytes.toInt()
    }
}
