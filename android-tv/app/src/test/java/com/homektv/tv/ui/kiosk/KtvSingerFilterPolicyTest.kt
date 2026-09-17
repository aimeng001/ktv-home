package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvSingerFilterPolicyTest {
    @Test
    fun exposesOnlyServerSupportedGenderValues() {
        assertEquals(listOf("", "男歌手", "女歌手", "组合"), KtvSingerFilterPolicy.filters.map { it.gender })
    }

    @Test
    fun selectionUsesTrimmedCurrentGender() {
        val female = KtvSingerFilterPolicy.filters.first { it.gender == "女歌手" }
        assertTrue(KtvSingerFilterPolicy.isSelected(female, " 女歌手 "))
    }
}
