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
        if (vocalMode.equals("original", ignoreCase = true)) return PcmChannelMode.STEREO
        val isAccompaniment = vocalMode.equals("accompaniment", ignoreCase = true)
        return when {
            isAccompaniment && accompanimentChannel.equals("RIGHT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
            isAccompaniment && accompanimentChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.LEFT_MONO
            isAccompaniment && originalChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
            isAccompaniment -> PcmChannelMode.LEFT_MONO
            else -> PcmChannelMode.STEREO
        }
    }
}
