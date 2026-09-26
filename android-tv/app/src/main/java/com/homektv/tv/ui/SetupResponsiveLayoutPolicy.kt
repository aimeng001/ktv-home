package com.homektv.tv.ui

internal object SetupResponsiveLayoutPolicy {
    fun useCompactLayout(isTelevision: Boolean, smallestWidthDp: Int): Boolean =
        !isTelevision && smallestWidthDp < COMPACT_WIDTH_DP

    private const val COMPACT_WIDTH_DP = 600
}

internal object StandbyRecommendationPolicy {
    fun isSectionVisible(songCount: Int): Boolean = songCount > 0
}
