package com.homektv.tv.ui

/** Decides whether an idle Kiosk timeout may switch an active ordering screen into MV playback. */
internal object KioskIdleTimeoutPolicy {
    fun shouldEnterFullscreen(
        autoFullscreenEnabled: Boolean,
        hasVisibleVideo: Boolean,
        externalDisplayActive: Boolean,
        isPlaying: Boolean,
        hasActiveModal: Boolean,
    ): Boolean = autoFullscreenEnabled &&
        hasVisibleVideo &&
        !externalDisplayActive &&
        isPlaying &&
        !hasActiveModal
}
