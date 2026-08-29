package com.homektv.tv.ui

/** Keeps the mini QR image and its explanatory label in sync. */
object StandbyQrPolicy {
    data class Visibility(val imageVisible: Boolean, val labelVisible: Boolean)

    fun visibility(enabled: Boolean): Visibility = Visibility(enabled, enabled)
}
