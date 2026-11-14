package com.homektv.tv.ui

data class DockWindowBounds(
    val isMatchParent: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    val bottomMarginDp: Int,
)

object DockFullscreenPolicy {
    const val PIP_WIDTH_DP = 185
    const val PIP_HEIGHT_DP = 104
    const val PIP_BOTTOM_MARGIN_DP = 0

    enum class VideoClickAction {
        ENTER_FULLSCREEN,
        SHOW_CONTROLS,
        IGNORE,
    }

    fun canEnterFullscreen(videoVisible: Boolean, externalDisplayActive: Boolean, isFullscreen: Boolean): Boolean {
        if (!videoVisible) return false
        if (externalDisplayActive) return false
        if (isFullscreen) return false
        return true
    }

    fun videoClickAction(
        videoVisible: Boolean,
        externalDisplayActive: Boolean,
        isFullscreen: Boolean,
        controlsVisible: Boolean,
    ): VideoClickAction {
        if (!videoVisible || externalDisplayActive) return VideoClickAction.IGNORE
        if (!isFullscreen) return VideoClickAction.ENTER_FULLSCREEN
        return if (!controlsVisible) VideoClickAction.SHOW_CONTROLS else VideoClickAction.IGNORE
    }

    fun shouldExitOnExternalDisplay(externalDisplayActive: Boolean, isFullscreen: Boolean): Boolean {
        return externalDisplayActive && isFullscreen
    }

    fun boundsFor(isFullscreen: Boolean): DockWindowBounds {
        return if (isFullscreen) {
            DockWindowBounds(
                isMatchParent = true,
                widthDp = -1,
                heightDp = -1,
                bottomMarginDp = 0,
            )
        } else {
            DockWindowBounds(
                isMatchParent = false,
                widthDp = PIP_WIDTH_DP,
                heightDp = PIP_HEIGHT_DP,
                bottomMarginDp = PIP_BOTTOM_MARGIN_DP,
            )
        }
    }
}
