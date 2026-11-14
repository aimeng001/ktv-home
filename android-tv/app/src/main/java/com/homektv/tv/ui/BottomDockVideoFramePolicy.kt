package com.homektv.tv.ui

data class BottomDockVideoFrameUiState(
    val containerVisible: Boolean,
    val useIdleFrame: Boolean,
    val showEdgeShadows: Boolean,
)

/** Controls bottom dock PiP frame styling and edge shadows across idle/active playback. */
object BottomDockVideoFramePolicy {
    fun resolve(effectiveVideoVisible: Boolean): BottomDockVideoFrameUiState =
        BottomDockVideoFrameUiState(
            containerVisible = true,
            useIdleFrame = !effectiveVideoVisible,
            showEdgeShadows = effectiveVideoVisible,
        )
}

/** Caches the last applied dock PiP frame state so 500ms snapshot renders skip redundant rebinds. */
class BottomDockFrameRenderCache {
    private var lastEffectiveVideoVisible: Boolean? = null

    fun shouldApply(effectiveVideoVisible: Boolean): Boolean {
        if (lastEffectiveVideoVisible == effectiveVideoVisible) return false
        lastEffectiveVideoVisible = effectiveVideoVisible
        return true
    }

    fun invalidate() {
        lastEffectiveVideoVisible = null
    }
}
