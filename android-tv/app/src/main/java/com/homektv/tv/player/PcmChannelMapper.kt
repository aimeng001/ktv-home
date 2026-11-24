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
        val isOriginal = vocalMode.equals("original", ignoreCase = true)
        val isAccompaniment = vocalMode.equals("accompaniment", ignoreCase = true)
        if (!isOriginal && !isAccompaniment) return PcmChannelMode.STEREO
        if (isOriginal) {
            return when {
                originalChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.LEFT_MONO
                originalChannel.equals("RIGHT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
                accompanimentChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
                accompanimentChannel.equals("RIGHT", ignoreCase = true) -> PcmChannelMode.LEFT_MONO
                else -> PcmChannelMode.RIGHT_MONO
            }
        }
        return when {
            accompanimentChannel.equals("RIGHT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
            accompanimentChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.LEFT_MONO
            originalChannel.equals("LEFT", ignoreCase = true) -> PcmChannelMode.RIGHT_MONO
            else -> PcmChannelMode.LEFT_MONO
        }
    }
}
