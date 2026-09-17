package com.homektv.tv.ui.kiosk

/** Commercial category entry points must expose the category/tag catalogue. */
internal enum class KtvCategoryRoute {
    TAGS,
    LANGUAGES,
    NEW,
}

internal object KtvCategoryRoutePolicy {
    fun dashboardCategory(): KtvCategoryRoute = KtvCategoryRoute.TAGS
}
