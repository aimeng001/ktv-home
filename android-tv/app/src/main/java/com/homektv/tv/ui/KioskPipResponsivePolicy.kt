package com.homektv.tv.ui

/** Sizes the kiosk PiP against the measured content stage instead of assuming one screen size. */
internal object KioskPipResponsivePolicy {
    data class Size(
        val widthDp: Int,
        val visible: Boolean,
    )

    fun resolve(
        availableWidthDp: Int,
        availableHeightDp: Int,
        minContentHeightDp: Int = MIN_CONTENT_HEIGHT_DP,
        bottomMarginDp: Int = BOTTOM_MARGIN_DP,
        contentGapDp: Int = CONTENT_GAP_DP,
        minWidthDp: Int = MIN_WIDTH_DP,
        maxWidthDp: Int = MAX_WIDTH_DP,
        widthFraction: Float = WIDTH_FRACTION,
    ): Size {
        val stageWidth = availableWidthDp.coerceAtLeast(0)
        val stageHeight = availableHeightDp.coerceAtLeast(0)
        val minimumWidth = minWidthDp.coerceAtLeast(1)
        val heightBudget = (
            stageHeight - minContentHeightDp.coerceAtLeast(0) -
                bottomMarginDp.coerceAtLeast(0) - contentGapDp.coerceAtLeast(0)
            )
            .coerceAtLeast(0)
        val heightLimitedWidth = (heightBudget * VIDEO_ASPECT_WIDTH / VIDEO_ASPECT_HEIGHT).toInt()
        val widthLimit = minOf(maxWidthDp.coerceAtLeast(1), stageWidth, heightLimitedWidth)
        if (widthLimit < minimumWidth) return Size(widthDp = 0, visible = false)

        val preferredWidth = (stageWidth * widthFraction.coerceIn(0f, 1f)).toInt()
            .coerceAtLeast(minimumWidth)
        return Size(widthDp = minOf(preferredWidth, widthLimit), visible = true)
    }

    // Keep the search prompt and one full T9 action row visible: 12 + 52 + 12 + 52 + 6 dp.
    private const val MIN_CONTENT_HEIGHT_DP = 134
    private const val BOTTOM_MARGIN_DP = 8
    private const val CONTENT_GAP_DP = 10
    private const val MIN_WIDTH_DP = 128
    private const val MAX_WIDTH_DP = 340
    // The reference frame is ultra-wide; retain 16:9 and match its height so the second tile row stays aligned.
    private const val WIDTH_FRACTION = 0.22f
    private const val VIDEO_ASPECT_WIDTH = 16
    private const val VIDEO_ASPECT_HEIGHT = 9
}
