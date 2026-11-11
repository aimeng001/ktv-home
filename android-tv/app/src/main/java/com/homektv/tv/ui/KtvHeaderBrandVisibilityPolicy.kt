package com.homektv.tv.ui

/** Keeps standby branding out of the five-action KTV toolbar while preserving non-kiosk standby branding. */
internal object KtvHeaderBrandVisibilityPolicy {
    fun shouldShowStandbyBrand(canOpenKiosk: Boolean): Boolean = !canOpenKiosk
}
