package com.homektv.tv.ui.kiosk

import com.homektv.tv.net.NamedCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KioskCommercialTagPolicyTest {
    @Test
    fun semanticRoutesRequireAnExactServerReturnedTag() {
        val tags = listOf(NamedCount(" 高清HD歌曲 ", 12), NamedCount("抖音热歌", 8))

        assertEquals("高清HD歌曲", KioskCommercialTagPolicy.findExactTag(tags, "高清HD歌曲")?.name?.trim())
        assertNull(KioskCommercialTagPolicy.findExactTag(tags, "抖音神曲"))
        assertNull(KioskCommercialTagPolicy.findExactTag(tags, "高清"))
    }

    @Test
    fun themeListExcludesOnlyExactDedicatedRoutesAndVarietyPrograms() {
        val tags = listOf(
            NamedCount("高清HD歌曲", 12),
            NamedCount("抖音神曲", 9),
            NamedCount("中国好声音", 4),
            NamedCount("精选专区", 7),
            NamedCount("怀旧", 30),
            NamedCount("抖音热歌", 3),
        )

        assertEquals(listOf("怀旧", "抖音热歌"), KioskCommercialTagPolicy.themeTags(tags).map { it.name })
    }
}
