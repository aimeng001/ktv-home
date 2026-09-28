package com.homektv.tv.ui

/** Measured content budgets used to keep PiP from reducing the active panel's usable height. */
internal data class KioskContentHeightRequirement(
    val preferredHeightDp: Int,
    val minimumScrollableViewportDp: Int,
)

internal object KioskContentRequirementPolicy {
    fun pinyin(
        keyboardNaturalHeightDp: Int,
        resultsMinimumHeightDp: Int,
        minimumScrollableViewportDp: Int,
    ): KioskContentHeightRequirement = requirement(
        preferredHeightDp = maxOf(keyboardNaturalHeightDp, resultsMinimumHeightDp),
        minimumScrollableViewportDp = minimumScrollableViewportDp,
    )

    fun listPage(
        chromeHeightDp: Int,
        firstItemRowHeightDp: Int,
        minimumScrollableViewportDp: Int,
    ): KioskContentHeightRequirement = requirement(
        preferredHeightDp = chromeHeightDp.coerceAtLeast(0) + firstItemRowHeightDp.coerceAtLeast(0),
        minimumScrollableViewportDp = minimumScrollableViewportDp,
    )

    fun dashboard(
        topRowHeightDp: Int,
        bottomRowHeightDp: Int,
        interRowAndOuterSpacingDp: Int,
    ): KioskContentHeightRequirement {
        val top = topRowHeightDp.coerceAtLeast(0)
        val bottom = bottomRowHeightDp.coerceAtLeast(0)
        return requirement(
            preferredHeightDp = top + bottom + interRowAndOuterSpacingDp.coerceAtLeast(0),
            minimumScrollableViewportDp = maxOf(top, bottom),
        )
    }

    private fun requirement(
        preferredHeightDp: Int,
        minimumScrollableViewportDp: Int,
    ) = KioskContentHeightRequirement(
        preferredHeightDp = maxOf(preferredHeightDp, minimumScrollableViewportDp, 1),
        minimumScrollableViewportDp = minimumScrollableViewportDp.coerceAtLeast(1),
    )
}
