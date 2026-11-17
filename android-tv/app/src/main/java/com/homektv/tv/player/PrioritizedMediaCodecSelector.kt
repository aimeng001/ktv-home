package com.homektv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.Locale

/**
 * Hardware-first [MediaCodecSelector] that can dynamically switch to preferring
 * local software decoders (`c2.android.*`, `OMX.google.*`, etc.) when the TV's
 * hardware decoder fails or stalls on a specific stream.
 */
@OptIn(UnstableApi::class)
internal class PrioritizedMediaCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    @Volatile
    var preferSoftware: Boolean = false

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): MutableList<MediaCodecInfo> {
        val decoders = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return orderDecoderInfos(decoders, preferSoftware).toMutableList()
    }

    companion object {
        fun orderDecoderInfos(
            decoders: List<MediaCodecInfo>,
            preferSoftware: Boolean,
        ): List<MediaCodecInfo> {
            if (decoders.size <= 1) return decoders
            val (software, hardware) = decoders.partition(::isSoftwareCodec)
            return if (preferSoftware) {
                software + hardware
            } else {
                hardware + software
            }
        }

        fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
            if (info.softwareOnly) return true
            if (info.hardwareAccelerated) return false
            return isSoftwareCodecName(info.name)
        }

        fun isSoftwareCodecName(name: String?): Boolean {
            val normalized = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (normalized.isEmpty()) return false
            return normalized.startsWith("omx.google.") ||
                normalized.startsWith("c2.android.") ||
                normalized.startsWith("omx.ffmpeg.") ||
                normalized.startsWith("omx.sec.avc.sw.") ||
                normalized.contains(".sw.") ||
                normalized.endsWith(".sw") ||
                (!normalized.startsWith("omx.") && !normalized.startsWith("c2."))
        }
    }
}
