package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvSingerRegionPolicyTest {
    @Test
    fun mapsAllDisplayedRegionChipsToSeparateRegionAndGenderValues() {
        assertEquals(
            listOf(
                KtvSingerRegionFilter("内地男", "内地", "男歌手"),
                KtvSingerRegionFilter("港台男", "港台", "男歌手"),
                KtvSingerRegionFilter("国外男", "国外", "男歌手"),
                KtvSingerRegionFilter("内地女", "内地", "女歌手"),
                KtvSingerRegionFilter("港台女", "港台", "女歌手"),
                KtvSingerRegionFilter("国外女", "国外", "女歌手"),
                KtvSingerRegionFilter("内地组合", "内地", "组合"),
                KtvSingerRegionFilter("港台组合", "港台", "组合"),
                KtvSingerRegionFilter("国外组合", "国外", "组合"),
            ),
            KtvSingerRegionPolicy.filters,
        )
    }

    @Test
    fun regionChipSelectionRequiresBothRegionAndGenderToMatch() {
        val filter = KtvSingerRegionPolicy.filters.first { it.label == "港台女" }

        assertTrue(KtvSingerRegionPolicy.isSelected(filter, "港台", "女歌手"))
        assertFalse(KtvSingerRegionPolicy.isSelected(filter, "内地", "女歌手"))
        assertFalse(KtvSingerRegionPolicy.isSelected(filter, "港台", "男歌手"))
    }
}
