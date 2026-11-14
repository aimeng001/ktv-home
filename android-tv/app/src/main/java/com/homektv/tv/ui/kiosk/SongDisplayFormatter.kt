package com.homektv.tv.ui.kiosk

/**
 * 端侧展示文本清洗工具：仅作用于 UI 渲染层，不修改服务端索引、数据库或搜索关键词。
 */
object SongDisplayFormatter {

    private val technicalTagPattern = Regex(
        """(?:\[|【)\s*(?:[^\]】]*(?:声道|原唱|伴奏|原版|高清|超清|蓝光|修复|无损|立体声|带字|纯音乐|卡拉OK|1080|720|4K|2K|HD|SD|DVD|VCD|KTV|MTV|MP4|MKV|AVI|TS|RMVB|MPG|VOB|FLAC|MP3))[^\]】]*(?:\]|】|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val unclosedTrailingBracketPattern = Regex("""\s*(?:\[|【)[^\]】]*$""")
    private val whitespaceCollapsePattern = Regex("""\s+""")
    private val slashSpacingPattern = Regex("""\s*/\s*""")

    fun formatTitle(rawTitle: String?): String {
        val raw = rawTitle?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        var cleaned = raw
            .replace(technicalTagPattern, " ")
            .replace(unclosedTrailingBracketPattern, " ")
            .replace('^', ' ')
            .replace('_', ' ')
            .replace(whitespaceCollapsePattern, " ")
            .trim(' ', '-', '/', '|', '·')

        if (cleaned.isEmpty()) {
            cleaned = raw
        }
        return cleaned
    }

    fun formatArtist(rawArtist: String?): String {
        val raw = rawArtist?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        var cleaned = raw
            .replace(technicalTagPattern, " ")
            .replace(unclosedTrailingBracketPattern, " ")
            .replace('^', '/')
            .replace('_', '/')
            .replace(slashSpacingPattern, "/")
            .replace(whitespaceCollapsePattern, " ")
            .trim(' ', '-', '/', '|', '·')

        if (cleaned.isEmpty()) {
            cleaned = raw
        }
        return cleaned
    }

    fun formatMediaLabel(mediaType: String?): String =
        when (mediaType.orEmpty().trim().uppercase()) {
            "KTV_VIDEO" -> "KTV"
            "MV" -> "MV"
            else -> ""
        }

    fun formatArtistSubtitle(rawArtist: String?, mediaType: String?): String {
        val artist = formatArtist(rawArtist)
        val label = formatMediaLabel(mediaType)
        return when {
            artist.isNotEmpty() && label.isNotEmpty() -> "$artist | $label"
            artist.isNotEmpty() -> artist
            label.isNotEmpty() -> label
            else -> ""
        }
    }
}
