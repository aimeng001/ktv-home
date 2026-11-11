package com.homektv.tv.ui

/** Positions the live PiP wholly inside the unobscured area beside the queue drawer. */
object QueueDrawerPipPositionPolicy {
    fun horizontalBias(
        parentWidthPx: Int,
        pipWidthPx: Int,
        drawerWidthPx: Int,
        drawerVisibleOnRight: Boolean,
    ): Float {
        if (parentWidthPx <= 0 || pipWidthPx <= 0 || drawerWidthPx <= 0) return 0.5f

        val freeRangePx = parentWidthPx - pipWidthPx
        if (freeRangePx <= 0) return 0.5f

        val safeWidthPx = (parentWidthPx - drawerWidthPx).coerceAtLeast(0)
        val safeStartPx = if (drawerVisibleOnRight) 0 else drawerWidthPx
        val pipStartPx = safeStartPx + ((safeWidthPx - pipWidthPx).coerceAtLeast(0) / 2f)
        return (pipStartPx / freeRangePx).coerceIn(0f, 1f)
    }
}
