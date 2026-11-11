package com.homektv.tv.ui

import com.homektv.tv.net.LibraryStatus

/** Only starts one opt-in external-library scan after the connected service confirms capability. */
internal object StartupScanPolicy {
    fun shouldRequest(
        enabled: Boolean,
        canOpenKiosk: Boolean,
        connected: Boolean,
        status: LibraryStatus?,
    ): Boolean {
        if (!enabled || !canOpenKiosk || !connected || status == null) return false
        return status.scanRequestSupported &&
            status.libraryMode == "EXTERNAL_READ_ONLY" &&
            status.rootState == "READABLE" &&
            status.rootIdentityState != "MISMATCH" &&
            status.scanState != "RUNNING"
    }
}
