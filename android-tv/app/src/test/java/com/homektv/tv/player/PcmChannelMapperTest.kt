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
    fun originalKeepsTheFullStereoMixAndAccompanimentUsesItsDeclaredChannel() {
        assertEquals(
            PcmChannelMode.STEREO,
            PcmChannelMapper.modeFor("original", "LEFT", "RIGHT"),
        )
        assertEquals(
            PcmChannelMode.RIGHT_MONO,
            PcmChannelMapper.modeFor("accompaniment", "LEFT", "RIGHT"),
        )
        assertEquals(
            PcmChannelMode.LEFT_MONO,
            PcmChannelMapper.modeFor("accompaniment", "RIGHT", "LEFT"),
        )
    }

    @Test
    fun unknownVocalRoleFallsBackToUnchangedStereo() {
        assertEquals(
            PcmChannelMode.STEREO,
            PcmChannelMapper.modeFor("unexpected", "LEFT", "RIGHT"),
        )
    }

    @Test
    fun accompanimentModeWithNullOrUnrecognizedChannelMustFallbackToLeftMono() {
        // 商业 KTV 核心契约：双声道音轨若未明确标明伴奏在哪个声道，伴唱模式必须默认选择左声道（左伴右唱保底）
        assertEquals(
            PcmChannelMode.LEFT_MONO,
            PcmChannelMapper.modeFor(
                vocalMode = "accompaniment",
                originalChannel = null,
                accompanimentChannel = null,
            ),
        )
        // 若原唱标为 LEFT，伴唱隐式推导为 RIGHT
        assertEquals(
            PcmChannelMode.RIGHT_MONO,
            PcmChannelMapper.modeFor(
                vocalMode = "accompaniment",
                originalChannel = "LEFT",
                accompanimentChannel = null,
            ),
        )
        // 原唱模式依然直通立体声
        assertEquals(
            PcmChannelMode.STEREO,
            PcmChannelMapper.modeFor(
                vocalMode = "original",
                originalChannel = null,
                accompanimentChannel = null,
            ),
        )
    }
}
