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
