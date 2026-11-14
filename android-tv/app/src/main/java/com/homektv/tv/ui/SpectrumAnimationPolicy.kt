package com.homektv.tv.ui

import android.view.View

/**
 * Shared scheduling and lifecycle visibility policy for animated spectrum views.
 * Uses [View.isShown] so ancestor visibility transitions (e.g. tab switches) pause redraws.
 */
object SpectrumAnimationPolicy {

    fun shouldScheduleNextFrame(isShown: Boolean, isAttachedToWindow: Boolean): Boolean =
        isShown && isAttachedToWindow

    fun shouldKickAnimation(isShown: Boolean, isAttachedToWindow: Boolean): Boolean =
        isShown && isAttachedToWindow
}

internal fun View.syncSpectrumAnimationVisibility() {
    if (SpectrumAnimationPolicy.shouldKickAnimation(isShown, isAttachedToWindow)) {
        postInvalidateOnAnimation()
    }
}
