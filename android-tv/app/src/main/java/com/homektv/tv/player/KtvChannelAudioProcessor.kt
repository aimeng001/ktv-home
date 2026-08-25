package com.homektv.tv.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Maps a decoded stereo PCM stream without changing its timing or format.
 *
 * The mode is read once per input buffer. Changing it therefore affects the next audio buffer
 * boundary and never requires reconfiguring the sink, reopening the media item, or seeking.
 */
@OptIn(UnstableApi::class)
class KtvChannelAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var mode: PcmChannelMode = PcmChannelMode.STEREO

    fun setMode(mode: PcmChannelMode) {
        this.mode = mode
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only transform decoded, interleaved stereo linear PCM. Other formats pass through
        // untouched rather than risking a channel-layout guess.
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.bytesPerFrame <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputFrameBytes = inputAudioFormat.bytesPerFrame
        val outputFrameBytes = outputAudioFormat.bytesPerFrame
        val inputStart = inputBuffer.position()
        val inputLimit = inputBuffer.limit()
        val frameCount = (inputLimit - inputStart) / inputFrameBytes
        if (frameCount == 0) return

        val bytesPerSample = inputFrameBytes / 2
        val output = replaceOutputBuffer(frameCount * outputFrameBytes)
        val selectedMode = mode
        var framePosition = inputStart
        repeat(frameCount) {
            val leftPosition = framePosition
            val rightPosition = framePosition + bytesPerSample
            when (selectedMode) {
                PcmChannelMode.STEREO -> {
                    copySample(inputBuffer, leftPosition, bytesPerSample, output)
                    copySample(inputBuffer, rightPosition, bytesPerSample, output)
                }
                PcmChannelMode.LEFT_MONO -> {
                    copySample(inputBuffer, leftPosition, bytesPerSample, output)
                    copySample(inputBuffer, leftPosition, bytesPerSample, output)
                }
                PcmChannelMode.RIGHT_MONO -> {
                    copySample(inputBuffer, rightPosition, bytesPerSample, output)
                    copySample(inputBuffer, rightPosition, bytesPerSample, output)
                }
            }
            framePosition += inputFrameBytes
        }
        inputBuffer.position(inputStart + frameCount * inputFrameBytes)
        output.flip()
    }

    private fun copySample(
        inputBuffer: ByteBuffer,
        position: Int,
        byteCount: Int,
        output: ByteBuffer,
    ) {
        val sample = inputBuffer.duplicate()
        sample.position(position)
        sample.limit(position + byteCount)
        output.put(sample)
    }
}
