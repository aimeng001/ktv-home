package com.homektv.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmChannelMapperTest {
    @Test
    fun stereoKeepsLeftAndRightSamples() {
        assertArrayEquals(
            intArrayOf(100, 200),
            PcmChannelMapper.mapStereoFrame(100, 200, PcmChannelMode.STEREO),
        )
    }

    @Test
    fun leftMonoDuplicatesLeftSample() {
        assertArrayEquals(
            intArrayOf(100, 100),
            PcmChannelMapper.mapStereoFrame(100, 200, PcmChannelMode.LEFT_MONO),
        )
    }

    @Test
    fun rightMonoDuplicatesRightSample() {
        assertArrayEquals(
            intArrayOf(200, 200),
            PcmChannelMapper.mapStereoFrame(100, 200, PcmChannelMode.RIGHT_MONO),
        )
    }

    @Test
    fun vocalRoleUsesPerSongChannelDefinition() {
        assertEquals(
            PcmChannelMode.LEFT_MONO,
            PcmChannelMapper.modeFor("original", "LEFT", "RIGHT"),
        )
        assertEquals(
            PcmChannelMode.RIGHT_MONO,
            PcmChannelMapper.modeFor("accompaniment", "LEFT", "RIGHT"),
        )
        assertEquals(
            PcmChannelMode.RIGHT_MONO,
            PcmChannelMapper.modeFor("original", "RIGHT", "LEFT"),
        )
    }

    @Test
    fun unknownVocalRoleFallsBackToUnchangedStereo() {
        assertEquals(
            PcmChannelMode.STEREO,
            PcmChannelMapper.modeFor("unexpected", "LEFT", "RIGHT"),
        )
    }
}
