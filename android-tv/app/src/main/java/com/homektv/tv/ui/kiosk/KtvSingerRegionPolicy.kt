package com.homektv.tv.ui.kiosk

/** Exact mapping from the displayed legacy region chips to Home artist filters. */
data class KtvSingerRegionFilter(
    val label: String,
    val region: String,
    val gender: String,
)

object KtvSingerRegionPolicy {
    val filters = listOf(
        KtvSingerRegionFilter("内地男", "内地", "男歌手"),
        KtvSingerRegionFilter("港台男", "港台", "男歌手"),
        KtvSingerRegionFilter("国外男", "国外", "男歌手"),
        KtvSingerRegionFilter("内地女", "内地", "女歌手"),
        KtvSingerRegionFilter("港台女", "港台", "女歌手"),
        KtvSingerRegionFilter("国外女", "国外", "女歌手"),
        KtvSingerRegionFilter("内地组合", "内地", "组合"),
        KtvSingerRegionFilter("港台组合", "港台", "组合"),
        KtvSingerRegionFilter("国外组合", "国外", "组合"),
    )

    fun isSelected(filter: KtvSingerRegionFilter, region: String, gender: String): Boolean =
        filter.region == region && filter.gender == gender
}
