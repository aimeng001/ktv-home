package com.homektv.tv.ui

/**
 * Tracks the last bound dock button drawable resource IDs so snapshot emissions
 * skip redundant drawable replacement and full-toolbar normalization work.
 */
class BottomDockIconCachePolicy {
    private var lastVocalIconRes: Int = 0
    private var lastPlayPauseIconRes: Int = 0

    fun shouldUpdateVocalIcon(iconRes: Int): Boolean {
        if (iconRes == lastVocalIconRes) return false
        lastVocalIconRes = iconRes
        return true
    }

    fun shouldUpdatePlayPauseIcon(iconRes: Int): Boolean {
        if (iconRes == lastPlayPauseIconRes) return false
        lastPlayPauseIconRes = iconRes
        return true
    }
}
