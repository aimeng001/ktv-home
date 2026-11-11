package com.homektv.tv.player

import androidx.media3.common.PlaybackParameters
import kotlin.math.pow

enum class PlaybackPitchStatus {
    APPLIED,
    NO_MEDIA,
    NOT_READY,
    UNSUPPORTED_ENGINE,
    PARAMETER_REJECTED,
}

data class PlaybackPitchResult(
    val status: PlaybackPitchStatus,
    val semitones: Int,
    val pitch: Float,
    val positionBeforeMs: Long? = null,
    val positionAfterMs: Long? = null,
) {
    val applied: Boolean get() = status == PlaybackPitchStatus.APPLIED
}

object PlaybackPitchPolicy {
    const val MIN_SEMITONES = -5
    const val MAX_SEMITONES = 5

    fun clamp(semitones: Int): Int = semitones.coerceIn(MIN_SEMITONES, MAX_SEMITONES)

    fun pitchRatio(semitones: Int): Float =
        2.0.pow(clamp(semitones) / 12.0).toFloat()

    fun parametersFor(current: PlaybackParameters, semitones: Int): PlaybackParameters =
        PlaybackParameters(
            current.speed,
            pitchRatio(semitones),
        )
}
