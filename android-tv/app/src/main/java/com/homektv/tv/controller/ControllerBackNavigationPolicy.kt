package com.homektv.tv.controller

enum class ControllerBackTarget {
    CLOSE_PLAYLIST_DETAIL,
    CLOSE_CATALOG_DETAIL,
    EXIT,
}

/** Resolves nested controller navigation before allowing the Activity to exit. */
object ControllerBackNavigationPolicy {
    fun resolve(playlistDetail: Boolean, catalogDetail: Boolean): ControllerBackTarget = when {
        playlistDetail -> ControllerBackTarget.CLOSE_PLAYLIST_DETAIL
        catalogDetail -> ControllerBackTarget.CLOSE_CATALOG_DETAIL
        else -> ControllerBackTarget.EXIT
    }
}
