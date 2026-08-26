package com.homektv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(androidx.media3.common.util.UnstableApi::class)
class KtvChannelAudioProcessorTest {
    @Test
    fun stereoLeavesPcm16SamplesUnchanged() {
        val processor = configuredProcessor()
        processor.setMode(PcmChannelMode.STEREO)

        assertEquals(intArrayOf(100, 200).toList(), processOneFrame(processor, 100, 200))
    }

    @Test
    fun mapsPcm16SamplesWithoutReconfiguringBetweenModeChanges() {
        val processor = configuredProcessor()

        processor.setMode(PcmChannelMode.LEFT_MONO)
        assertEquals(intArrayOf(100, 100).toList(), processOneFrame(processor, 100, 200))

        processor.setMode(PcmChannelMode.RIGHT_MONO)
        assertEquals(intArrayOf(200, 200).toList(), processOneFrame(processor, 100, 200))
    }

    private fun configuredProcessor(): KtvChannelAudioProcessor {
        val processor = KtvChannelAudioProcessor()
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        assertTrue(processor.isActive)
        return processor
    }

    private fun processOneFrame(
        processor: KtvChannelAudioProcessor,
        left: Int,
        right: Int,
    ): List<Int> {
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            .putShort(left.toShort())
            .putShort(right.toShort())
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return listOf(output.short.toInt(), output.short.toInt())
    }
}
