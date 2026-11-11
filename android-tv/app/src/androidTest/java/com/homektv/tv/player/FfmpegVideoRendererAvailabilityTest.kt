package com.homektv.tv.player

import android.os.Handler
import android.os.Looper
import android.os.Build
import android.media.MediaCodecList
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegVideoRendererAvailabilityTest {
    @Test
    fun optionalFfmpegRendererMatchesBundledAbiAndPlatformFallbackRemainsAvailable() {
        val processAbi = Build.SUPPORTED_ABIS.firstOrNull()
            ?: error("Android did not report a process ABI")
        val bundledAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val expectedFfmpegAvailability = processAbi in bundledAbis
        val nativeFfmpegAvailable = FfmpegLibrary.isAvailable()

        assertEquals(
            "FFmpeg JNI availability must match the AAR's documented ABI set for $processAbi",
            expectedFfmpegAvailability,
            nativeFfmpegAvailable,
        )
        val renderer = createFfmpegVideoRenderer(
            allowedVideoJoiningTimeMs = 0L,
            eventHandler = Handler(Looper.getMainLooper()),
            eventListener = null,
        )

        if (expectedFfmpegAvailability) {
            assertNotNull("FFmpeg video renderer must be constructible for $processAbi", renderer)
            assertTrue(
                "Unexpected renderer: ${renderer?.name}",
                renderer?.name?.contains("ffmpeg", ignoreCase = true) == true,
            )
        } else {
            assertNull("Unsupported $processAbi must use the platform renderer fallback", renderer)
            val platformAvcDecoder = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
            }
            assertNotNull("Platform AVC playback fallback must remain available on $processAbi", platformAvcDecoder)
        }
    }
}
