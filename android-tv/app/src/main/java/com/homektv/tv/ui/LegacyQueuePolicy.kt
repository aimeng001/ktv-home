package com.homektv.tv.ui

/**
 * Policy governing the legacy remote queue overlay vs. the full Kiosk queue drawer.
 * Unprivileged TV players and legacy overlays are kept read-only; mutation is delegated
 * to the authenticated Kiosk queue drawer when available.
 */
object LegacyQueuePolicy {
    fun shouldAllowMutations(canOpenKiosk: Boolean): Boolean = false

    fun shouldOpenKioskDrawer(canOpenKiosk: Boolean, isKioskInitialized: Boolean): Boolean =
        canOpenKiosk && isKioskInitialized
}
