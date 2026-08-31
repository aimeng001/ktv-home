package com.homektv.tv.ui

/** A prompt is useful only when the server advertises a strictly newer build. */
internal fun isServerUpdateAvailable(currentVersionCode: Long, serverVersionCode: Long): Boolean =
    serverVersionCode > currentVersionCode
