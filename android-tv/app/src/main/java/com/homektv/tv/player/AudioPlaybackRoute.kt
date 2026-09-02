package com.homektv.tv.player

/** Selects the client-local playback mechanism for a server audio layout. */
enum class AudioPlaybackRoute {
    PASSTHROUGH,
    TRACK_SELECTION,
    PCM_CHANNEL_MAPPING;

    companion object {
        fun forLayout(layout: String?, audioTracks: Int = 1): AudioPlaybackRoute = when {
            audioTracks > 1 || layout.equals("DUAL_TRACK", ignoreCase = true) -> TRACK_SELECTION
            layout.equals("DUAL_CHANNEL", ignoreCase = true) -> PCM_CHANNEL_MAPPING
            else -> PASSTHROUGH
        }
    }
}
