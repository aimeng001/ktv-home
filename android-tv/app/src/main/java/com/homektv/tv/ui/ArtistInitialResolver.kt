package com.homektv.tv.ui

/**
 * 歌手首字符/首字解析器，用于无头像时生成占位文字徽章。
 */
object ArtistInitialResolver {
    fun resolve(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return "#"
        val firstChar = trimmed.first()
        return if (firstChar.isLetterOrDigit()) {
            firstChar.uppercase()
        } else {
            firstChar.toString()
        }
    }
}
