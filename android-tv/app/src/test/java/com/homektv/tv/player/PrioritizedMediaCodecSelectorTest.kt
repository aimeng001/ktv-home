package com.homektv.tv.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritizedMediaCodecSelectorTest {

    private fun codecInfo(
        name: String,
        hardwareAccelerated: Boolean,
        softwareOnly: Boolean,
    ): MediaCodecInfo = MediaCodecInfo.newInstance(
        /* name = */ name,
        /* mimeType = */ MimeTypes.VIDEO_H264,
        /* codecMimeType = */ MimeTypes.VIDEO_H264,
        /* capabilities = */ null,
        /* hardwareAccelerated = */ hardwareAccelerated,
        /* softwareOnly = */ softwareOnly,
        /* vendor = */ !softwareOnly,
        /* forceDisableAdaptive = */ false,
        /* forceSecure = */ false,
    )

    @Test
    fun hardwarePreferredPutsHardwareDecodersAheadOfSoftwareFallback() {
        val sw = codecInfo("c2.android.avc.decoder", hardwareAccelerated = false, softwareOnly = true)
        val hw = codecInfo("c2.amlogic.avc.decoder", hardwareAccelerated = true, softwareOnly = false)

        val ordered = PrioritizedMediaCodecSelector.orderDecoderInfos(
            decoders = listOf(sw, hw),
            preferSoftware = false,
        )

        assertEquals(listOf("c2.amlogic.avc.decoder", "c2.android.avc.decoder"), ordered.map { it.name })
    }

    @Test
    fun softwarePreferredPutsLocalSoftwareDecodersAheadOfHardware() {
        val hw = codecInfo("OMX.amlogic.avc.decoder.awesome", hardwareAccelerated = true, softwareOnly = false)
        val sw = codecInfo("OMX.google.h264.decoder", hardwareAccelerated = false, softwareOnly = true)

        val ordered = PrioritizedMediaCodecSelector.orderDecoderInfos(
            decoders = listOf(hw, sw),
            preferSoftware = true,
        )

        assertEquals(listOf("OMX.google.h264.decoder", "OMX.amlogic.avc.decoder.awesome"), ordered.map { it.name })
    }

    @Test
    fun softwareCodecNameRecognitionCoversPlatformConventions() {
        assertTrue(PrioritizedMediaCodecSelector.isSoftwareCodecName("c2.android.mpeg2.decoder"))
        assertTrue(PrioritizedMediaCodecSelector.isSoftwareCodecName("OMX.google.h264.decoder"))
        assertTrue(PrioritizedMediaCodecSelector.isSoftwareCodecName("OMX.SEC.avc.sw.dec"))
        assertFalse(PrioritizedMediaCodecSelector.isSoftwareCodecName("c2.amlogic.mpeg2.decoder"))
        assertFalse(PrioritizedMediaCodecSelector.isSoftwareCodecName("OMX.MTK.VIDEO.DECODER.AVC"))
    }
}
