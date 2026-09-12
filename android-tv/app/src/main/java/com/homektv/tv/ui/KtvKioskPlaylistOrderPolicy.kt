package com.homektv.tv.ui

/** Button state for the one-flight playlist order request. */
object KtvKioskPlaylistOrderPolicy {
    data class ButtonState(val text: String, val isEnabled: Boolean)

    fun resolve(isPending: Boolean): ButtonState = if (isPending) {
        ButtonState("整单点歌中…", isEnabled = false)
    } else {
        ButtonState("整单点歌", isEnabled = true)
    }
}
