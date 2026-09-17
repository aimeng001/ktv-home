package com.homektv.tv.ui.kiosk

/**
 * The artist directory currently has a trusted gender dimension.  Keep the
 * filter values centralized so the kiosk labels cannot drift from the API
 * values used by [ControllerCatalogActions].
 */
object KtvSingerFilterPolicy {
    data class Filter(val label: String, val gender: String)

    val filters: List<Filter> = listOf(
        Filter("全部", ""),
        Filter("男歌手", "男歌手"),
        Filter("女歌手", "女歌手"),
        Filter("组合", "组合"),
    )

    fun isSelected(filter: Filter, gender: String): Boolean = filter.gender == gender.trim()
}
