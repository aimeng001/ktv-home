package com.homektv.tv.ui.kiosk

/**
 * Specification for a reusable D-pad rail option button inside the 3-column browser.
 */
class BrowserRailOptionSpec(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * Decides whether existing rail buttons can be updated in place without removeAllViews(),
 * preserving D-pad focus and avoiding view allocation churn across state updates.
 */
object BrowserRailSyncPolicy {

    fun canReuseButtons(existingLabels: List<String>, next: List<BrowserRailOptionSpec>): Boolean {
        if (existingLabels.size != next.size) return false
        for (i in existingLabels.indices) {
            if (existingLabels[i] != next[i].label) return false
        }
        return true
    }
}
