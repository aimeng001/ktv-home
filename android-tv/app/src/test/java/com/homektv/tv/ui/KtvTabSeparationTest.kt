package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvTabSeparationTest {

    @Test
    fun tabState_switchesTabsCleanly() {
        val state = KioskPresentationState()

        state.selectTab(KioskTab.RANKINGS)
        assertEquals(KioskTab.RANKINGS, state.currentTab.value)

        state.selectTab(KioskTab.CATEGORIES)
        assertEquals(KioskTab.CATEGORIES, state.currentTab.value)

        state.selectTab(KioskTab.RANKINGS)
        assertEquals(KioskTab.RANKINGS, state.currentTab.value)
    }

    @Test
    fun selectArtist_switchesToPinyinTabAndPreservesArtistName() {
        val state = KioskPresentationState()
        state.selectArtist("周杰伦")

        assertEquals(KioskTab.PINYIN, state.currentTab.value)
        assertEquals("周杰伦", state.selectedArtist.value)

        state.clearArtistSelection()
        assertTrue(state.selectedArtist.value == null)
    }

    @Test
    fun switchingAwayFromAndBackToPinyinKeepsKeyboardAndQuerySynchronized() {
        var current = java.io.File(System.getProperty("user.dir").orEmpty())
        val controllerFile = generateSequence(current) { it.parentFile }
            .take(6)
            .mapNotNull { dir ->
                listOf(
                    java.io.File(dir, "src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
                    java.io.File(dir, "android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt"),
                ).firstOrNull(java.io.File::isFile)
            }
            .first()
        val source = controllerFile.readText()
        val tabSection = source.substringAfter("when (tab) {")
            .substringBefore("KioskTab.RANKINGS -> if (!rankingsLoaded)")
        assertTrue(
            "返回 PINYIN 页时必须将键盘输入内容与 controllerState.query 同步，防止旧词残留",
            tabSection.contains("overlay.kioskKeyboard.setKeyword(") ||
                source.substringAfter("if (tab != KioskTab.PINYIN)").substringBefore("when (tab)")
                    .contains("catalogActions.setQuery(\"\")"),
        )
        assertTrue(
            "PINYIN 分支应同步 setKeyword 保证状态一致性",
            tabSection.contains("setKeyword("),
        )
    }
}
