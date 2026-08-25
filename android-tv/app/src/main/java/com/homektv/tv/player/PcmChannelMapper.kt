package com.homektv.tv.player

/** The two-channel output shape used by a dual-channel karaoke stream. */
enum class PcmChannelMode {
    STEREO,
    LEFT_MONO,
    RIGHT_MONO,
}

/** Pure channel-selection rules kept separate from the Media3 buffer plumbing. */
object PcmChannelMapper {
    fun mapStereoFrame(left: Int, right: Int, mode: PcmChannelMode): IntArray = when (mode) {
        PcmChannelMode.STEREO -> intArrayOf(left, right)
        PcmChannelMode.LEFT_MONO -> intArrayOf(left, left)
        PcmChannelMode.RIGHT_MONO -> intArrayOf(right, right)
    }

    /** Converts the server's platform-neutral vocal semantics into a PCM output mode. */
    fun modeFor(
        vocalMode: String?,
        originalChannel: String?,
        accompanimentChannel: String?,
    ): PcmChannelMode {
        val selectedChannel = when {
            vocalMode.equals("original", ignoreCase = true) -> originalChannel
            vocalMode.equals("accompaniment", ignoreCase = true) -> accompanimentChannel
            else -> null
        }
        return when {
            selectedChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.LEFT_MONO
            selectedChannel.equals("RIGHT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
            else -> PcmChannelMode.STEREO
        }
    }
}
