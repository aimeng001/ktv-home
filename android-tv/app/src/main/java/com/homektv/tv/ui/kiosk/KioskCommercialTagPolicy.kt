package com.homektv.tv.ui.kiosk

import com.homektv.tv.net.NamedCount
import java.util.Locale

/** Exact Home KTV tag matches for reference categories; unknown aliases are never guessed. */
object KioskCommercialTagPolicy {
    const val HIGH_DEFINITION_TAG = "高清HD歌曲"
    const val TIKTOK_TAG = "抖音神曲"
    const val FEATURED_TAG = "精选专区"

    val varietyProgramNames: List<String> = listOf(
        "中国好声音",
        "中国好歌曲",
        "中国梦之声",
        "中国最强音",
        "最美和声",
        "我为歌狂",
        "我是歌手",
        "全能星战",
    )

    private val specialRouteTags = setOf(
        HIGH_DEFINITION_TAG,
        TIKTOK_TAG,
        FEATURED_TAG,
    ) + varietyProgramNames
    private val normalizedSpecialRouteTags = specialRouteTags.mapTo(mutableSetOf(), ::normalize)

    fun findExactTag(tags: List<NamedCount>, expectedName: String): NamedCount? {
        val expected = normalize(expectedName)
        return tags.firstOrNull { normalize(it.name) == expected }
    }

    fun themeTags(tags: List<NamedCount>): List<NamedCount> = tags.filterNot { item ->
        normalize(item.name) in normalizedSpecialRouteTags
    }

    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
