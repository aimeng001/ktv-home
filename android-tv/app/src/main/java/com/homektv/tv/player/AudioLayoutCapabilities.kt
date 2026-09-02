package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout

/** Platform-neutral capability checks shared by playback UI and engine routing. */
internal fun supportsVocalSwitch(layout: AudioLayout?, audioTracks: Int = 1): Boolean =
    audioTracks > 1 ||
        layout?.layout?.equals("DUAL_TRACK", ignoreCase = true) == true ||
        layout?.layout?.equals("DUAL_CHANNEL", ignoreCase = true) == true
