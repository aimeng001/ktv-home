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

    @Test
    fun missingTagNoticeGuidesKeyboardSearchWhenKeyboardIsShowing() {
        val notice = KioskCommercialTagPolicy.noticeForMissingTag("抖音神曲", keyboardShowing = true)
        assertEquals("当前曲库没有精确的“抖音神曲”标签；可通过右侧拼音键盘检索点歌。", notice)
    }

    @Test
    fun missingTagNoticeGuidesTagSelectionWhenKeyboardIsHidden() {
        val notice = KioskCommercialTagPolicy.noticeForMissingTag("高清HD歌曲", keyboardShowing = false)
        assertEquals("当前曲库没有精确的“高清HD歌曲”标签；可从现有标签中选择。", notice)
    }

    @Test
    fun loadFailureNoticeAdaptsToKeyboardVisibility() {
        assertEquals(
            "标签列表加载失败，暂时无法确认对应分类；可通过拼音键盘检索点歌。",
            KioskCommercialTagPolicy.noticeForLoadFailure(keyboardShowing = true)
        )
        assertEquals(
            "标签列表加载失败，暂时无法确认对应分类；可按确定重试。",
            KioskCommercialTagPolicy.noticeForLoadFailure(keyboardShowing = false)
        )
    }
}
