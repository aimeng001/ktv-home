package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout

/** Resolves server track semantics while retaining the legacy two-track fallback. */
internal fun resolveAudioTrackIndex(
    audioLayout: AudioLayout,
    vocalMode: String,
    audioTrackCount: Int,
    legacyAccompanimentIndex: Int?,
): Int? {
    if (audioTrackCount <= 0) return null

    val explicitIndex = when {
        vocalMode.equals("original", ignoreCase = true) -> audioLayout.originalTrackIndex
        vocalMode.equals("accompaniment", ignoreCase = true) -> audioLayout.accompanimentTrackIndex
        else -> null
    }
    if (explicitIndex != null && explicitIndex in 0 until audioTrackCount) {
        return explicitIndex
    }

    val fallback = if (vocalMode.equals("accompaniment", ignoreCase = true)) {
        legacyAccompanimentIndex
    } else if (legacyAccompanimentIndex == 0 && audioTrackCount > 1) {
        1
    } else {
        0
    }
    return fallback?.takeIf { it in 0 until audioTrackCount }
}
