package com.homektv.tv.ui

/** Measured content budgets used to keep PiP from reducing the active panel's usable height. */
internal data class KioskContentHeightRequirement(
    val preferredHeightDp: Int,
    val minimumHeightDp: Int,
)

internal object KioskContentRequirementPolicy {
    fun pinyin(
        keyboardNaturalHeightDp: Int,
        resultsMinimumHeightDp: Int,
        minimumScrollableViewportDp: Int,
    ): KioskContentHeightRequirement = requirement(
        preferredHeightDp = maxOf(keyboardNaturalHeightDp, resultsMinimumHeightDp),
        minimumHeightDp = maxOf(resultsMinimumHeightDp, minimumScrollableViewportDp),
    )

    fun listPage(
        chromeHeightDp: Int,
        firstItemRowHeightDp: Int,
        minimumScrollableViewportDp: Int,
    ): KioskContentHeightRequirement {
        val minimumContentHeight = chromeHeightDp.coerceAtLeast(0) +
            maxOf(firstItemRowHeightDp.coerceAtLeast(0), minimumScrollableViewportDp.coerceAtLeast(1))
        return requirement(
            preferredHeightDp = minimumContentHeight,
            minimumHeightDp = minimumContentHeight,
        )
    }

    fun dashboard(
        topRowHeightDp: Int,
        bottomRowHeightDp: Int,
        interRowAndOuterSpacingDp: Int,
    ): KioskContentHeightRequirement {
        val top = topRowHeightDp.coerceAtLeast(0)
        val bottom = bottomRowHeightDp.coerceAtLeast(0)
        val height = top + bottom + interRowAndOuterSpacingDp.coerceAtLeast(0)
        return requirement(preferredHeightDp = height, minimumHeightDp = height)
    }

    private fun requirement(
        preferredHeightDp: Int,
        minimumHeightDp: Int,
    ): KioskContentHeightRequirement {
        val preferredHeight = preferredHeightDp.coerceAtLeast(1)
        return KioskContentHeightRequirement(
            preferredHeightDp = preferredHeight,
            minimumHeightDp = minimumHeightDp.coerceAtLeast(1).coerceAtMost(preferredHeight),
        )
    }
}
