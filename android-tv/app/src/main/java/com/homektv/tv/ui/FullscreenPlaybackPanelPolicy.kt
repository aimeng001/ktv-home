package com.homektv.tv.ui

import kotlin.math.min
import kotlin.math.roundToInt

enum class FullscreenPanelKey {
    BACK, CENTER, ENTER, MENU, UP, DOWN, LEFT, RIGHT, VOLUME, OTHER,
}

enum class FullscreenPanelKeyAction {
    DOWN, UP,
}

enum class FullscreenPanelFocusTarget {
    PAUSE_PLAY, REWIND, FORWARD,
}

enum class FullscreenPanelKeyEffect {
    PASS, CONSUME, SHOW_CONTROLS, HIDE_CONTROLS, EXIT_FULLSCREEN, MOVE_FOCUS, CONFIRM, RESET_TIMEOUT,
}

enum class FullscreenPanelDirection {
    UP, DOWN, LEFT, RIGHT,
}

enum class FullscreenPlaybackAction {
    PAUSE_PLAY, VOCAL, RESTART, NEXT, VOLUME_DOWN, VOLUME_UP, MUTE, KIOSK, QUEUE, HOME, PITCH,
}

data class FullscreenPanelKeyDecision(
    val effect: FullscreenPanelKeyEffect,
    val focusTarget: FullscreenPanelFocusTarget? = null,
    val direction: FullscreenPanelDirection? = null,
)

/** Pure remote-key policy so reveal, confirmation, and layered Back stay independently testable. */
class FullscreenPlaybackPanelKeyRouter {
    private val revealKeyReleases = mutableSetOf<FullscreenPanelKey>()
    private val confirmKeyReleases = mutableSetOf<FullscreenPanelKey>()

    fun route(
        fullscreen: Boolean,
        controlsVisible: Boolean,
        key: FullscreenPanelKey,
        action: FullscreenPanelKeyAction,
        repeatCount: Int = 0,
    ): FullscreenPanelKeyDecision {
        if (!fullscreen) return PASS
        if (key == FullscreenPanelKey.VOLUME || key == FullscreenPanelKey.OTHER) return PASS

        if (key == FullscreenPanelKey.BACK) {
            return if (action == FullscreenPanelKeyAction.DOWN) CONSUME
            else if (controlsVisible) HIDE else EXIT
        }

        if (key == FullscreenPanelKey.MENU) {
            if (controlsVisible) {
                if (consumeRevealRelease(key, action)) return CONSUME
                return if (action == FullscreenPanelKeyAction.DOWN) RESET_TIMEOUT else CONSUME
            }
            return reveal(key, action, FullscreenPanelFocusTarget.PAUSE_PLAY)
        }

        if (key == FullscreenPanelKey.CENTER || key == FullscreenPanelKey.ENTER) {
            if (!controlsVisible) return reveal(key, action, FullscreenPanelFocusTarget.PAUSE_PLAY)
            if (consumeRevealRelease(key, action)) return CONSUME
            if (action == FullscreenPanelKeyAction.DOWN) {
                if (repeatCount == 0) confirmKeyReleases.add(key)
                return CONSUME
            }
            return if (confirmKeyReleases.remove(key) && repeatCount == 0) CONFIRM else CONSUME
        }

        val direction = when (key) {
            FullscreenPanelKey.UP -> FullscreenPanelDirection.UP
            FullscreenPanelKey.DOWN -> FullscreenPanelDirection.DOWN
            FullscreenPanelKey.LEFT -> FullscreenPanelDirection.LEFT
            FullscreenPanelKey.RIGHT -> FullscreenPanelDirection.RIGHT
            else -> null
        } ?: return PASS

        if (!controlsVisible) {
            val focus = when (direction) {
                FullscreenPanelDirection.LEFT -> FullscreenPanelFocusTarget.REWIND
                FullscreenPanelDirection.RIGHT -> FullscreenPanelFocusTarget.FORWARD
                else -> FullscreenPanelFocusTarget.PAUSE_PLAY
            }
            return reveal(key, action, focus)
        }

        if (consumeRevealRelease(key, action)) return CONSUME
        return if (action == FullscreenPanelKeyAction.DOWN) {
            FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.MOVE_FOCUS, direction = direction)
        } else {
            CONSUME
        }
    }

    fun reset() {
        revealKeyReleases.clear()
        confirmKeyReleases.clear()
    }

    private fun reveal(
        key: FullscreenPanelKey,
        action: FullscreenPanelKeyAction,
        target: FullscreenPanelFocusTarget,
    ): FullscreenPanelKeyDecision {
        if (action == FullscreenPanelKeyAction.DOWN) {
            if (revealKeyReleases.add(key)) {
                return FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.SHOW_CONTROLS, focusTarget = target)
            }
        } else {
            revealKeyReleases.remove(key)
        }
        return CONSUME
    }

    private fun consumeRevealRelease(key: FullscreenPanelKey, action: FullscreenPanelKeyAction): Boolean {
        if (!revealKeyReleases.contains(key)) return false
        if (action == FullscreenPanelKeyAction.UP) revealKeyReleases.remove(key)
        return true
    }

    companion object {
        private val PASS = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.PASS)
        private val CONSUME = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.CONSUME)
        private val HIDE = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.HIDE_CONTROLS)
        private val EXIT = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.EXIT_FULLSCREEN)
        private val CONFIRM = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.CONFIRM)
        private val RESET_TIMEOUT = FullscreenPanelKeyDecision(FullscreenPanelKeyEffect.RESET_TIMEOUT)
    }
}

data class FullscreenPanelMetrics(
    val scale: Float,
    val panelHeightPx: Int,
    val progressRowHeightPx: Int,
    val actionRowHeightPx: Int,
    val actionGroupWidthPx: Int,
    val actionItemWidthPx: Int,
    val focusCircleSizePx: Int,
    val actionIconSizePx: Int,
    val actionLabelTextPx: Int,
    val timeTextPx: Int,
    val progressTrackHeightPx: Int,
    val progressButtonSizePx: Int,
    val progressHorizontalMarginPx: Int,
)

object FullscreenPlaybackPanelPolicy {
    const val AUTO_HIDE_DELAY_MS = 5_000L
    const val ACTION_COUNT = 10
    val ACTION_ORDER = listOf(
        FullscreenPlaybackAction.PAUSE_PLAY,
        FullscreenPlaybackAction.VOCAL,
        FullscreenPlaybackAction.RESTART,
        FullscreenPlaybackAction.NEXT,
        FullscreenPlaybackAction.VOLUME_DOWN,
        FullscreenPlaybackAction.VOLUME_UP,
        FullscreenPlaybackAction.MUTE,
        FullscreenPlaybackAction.KIOSK,
        FullscreenPlaybackAction.QUEUE,
        FullscreenPlaybackAction.HOME,
    )

    fun metrics(widthPx: Int, heightPx: Int): FullscreenPanelMetrics {
        val widthScale = widthPx.coerceAtLeast(1) / 1920f
        val heightScale = heightPx.coerceAtLeast(1) / 1080f
        val scale = min(widthScale, heightScale)
        fun px(referencePx: Int) = (referencePx * scale).roundToInt().coerceAtLeast(1)
        return FullscreenPanelMetrics(
            scale = scale,
            panelHeightPx = px(210),
            progressRowHeightPx = px(70),
            actionRowHeightPx = px(140),
            actionGroupWidthPx = px(1_400),
            actionItemWidthPx = px(140),
            focusCircleSizePx = px(70),
            actionIconSizePx = px(50),
            actionLabelTextPx = px(21),
            timeTextPx = px(26),
            progressTrackHeightPx = px(5),
            progressButtonSizePx = px(60),
            progressHorizontalMarginPx = px(50),
        )
    }

    fun progressPermille(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val position = positionMs.coerceIn(0L, durationMs)
        return ((position * 1_000L) / durationMs).toInt().coerceIn(0, 1_000)
    }

    fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3_600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(totalSeconds / 60, seconds)
        }
    }
}
