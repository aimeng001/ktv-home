package com.homektv.tv.ui.kiosk

/**
 * 点歌大厅与画中画小窗多分辨率/小屏设备自适应测量策略。
 * 解决手机横屏（~650-750dp）下 PIP 抢占过多宽度导致大厅 8 磁贴标题截断的问题。
 */
object KtvDashboardResponsivePolicy {
    const val MIN_PIP_WIDTH_DP = 200
    const val MAX_PIP_WIDTH_DP = 340
    const val DEFAULT_TILE_PADDING_HORIZONTAL_DP = 10
    const val DEFAULT_TILE_MARGIN_DP = 4

    fun calculatePipWidthDp(screenWidthDp: Int): Int {
        if (screenWidthDp <= 760) {
            val candidate = (screenWidthDp * 0.30f).toInt()
            return candidate.coerceIn(MIN_PIP_WIDTH_DP, 240)
        }
        val candidate = (screenWidthDp * 0.32f).toInt()
        return candidate.coerceIn(MIN_PIP_WIDTH_DP, MAX_PIP_WIDTH_DP)
    }

    fun calculateTileUsableWidthDp(
        screenWidthDp: Int,
        pipWidthDp: Int,
        columnCount: Int = 4,
        screenPaddingHorizontalDp: Int = 48,
        pipMarginStartDp: Int = 14,
        gridPaddingDp: Int = 16,
        tileMarginDp: Int = DEFAULT_TILE_MARGIN_DP,
        tilePaddingHorizontalDp: Int = DEFAULT_TILE_PADDING_HORIZONTAL_DP
    ): Float {
        val totalAvailable = screenWidthDp - screenPaddingHorizontalDp - pipMarginStartDp - pipWidthDp - gridPaddingDp
        val colWidth = totalAvailable.toFloat() / columnCount
        return colWidth - (tileMarginDp * 2) - (tilePaddingHorizontalDp * 2)
    }

    fun canFitTitleWithoutTruncation(title: String, usableWidthDp: Float, textSizeSp: Float = 16f): Boolean {
        // CJK 汉字宽度约为 1em = textSizeSp
        val requiredWidth = title.length * textSizeSp
        return usableWidthDp >= requiredWidth
    }
}
