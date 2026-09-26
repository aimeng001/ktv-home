package com.homektv.tv.ui

/** Describes what the kiosk PiP card may claim when the single player is on HDMI. */
internal object KioskPipDisplayPolicy {
    data class State(
        val videoVisible: Boolean,
        val label: String,
    )

    fun resolve(
        externalDisplayActive: Boolean,
        playbackState: String?,
        hasPlaying: Boolean,
    ): State {
        if (externalDisplayActive) {
            return State(videoVisible = false, label = "视频正在外接屏播放")
        }
        return State(
            videoVisible = hasPlaying && playbackState != "idle",
            label = PipLabelPolicy.resolve(playbackState, hasPlaying),
        )
    }
}
