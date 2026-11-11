package com.homektv.tv.player

import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPitchPolicyTest {
    @Test
    fun oneSemitoneUsesTwelveToneRatioAndKeepsPlaybackSpeed() {
        val current = PlaybackParameters(1.25f, 1f)

        val up = PlaybackPitchPolicy.parametersFor(current, semitones = 1)
        val down = PlaybackPitchPolicy.parametersFor(current, semitones = -1)

        assertEquals(1.25f, up.speed, 0f)
        assertEquals(1.0594631f, up.pitch, 0.0001f)
        assertEquals(1.25f, down.speed, 0f)
        assertEquals(0.9438743f, down.pitch, 0.0001f)
    }

    @Test
    fun requestedPitchIsClampedToFiveSemitones() {
        val current = PlaybackParameters(1f, 1f)

        assertEquals(
            PlaybackPitchPolicy.parametersFor(current, semitones = 5).pitch,
            PlaybackPitchPolicy.parametersFor(current, semitones = 50).pitch,
            0f,
        )
        assertEquals(
            PlaybackPitchPolicy.parametersFor(current, semitones = -5).pitch,
            PlaybackPitchPolicy.parametersFor(current, semitones = -50).pitch,
            0f,
        )
    }
}
