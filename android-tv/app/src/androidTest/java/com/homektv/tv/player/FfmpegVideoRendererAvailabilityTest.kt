package com.homektv.tv.player

import android.os.Handler
import android.os.Looper
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegVideoRendererAvailabilityTest {
    @Test
    fun bundledNativeDecoderAndVideoRendererAreAvailableForTheInstalledAbi() {
        assertTrue("FFmpeg JNI must load for the installed APK ABI", FfmpegLibrary.isAvailable())
        val renderer = createFfmpegVideoRenderer(
            allowedVideoJoiningTimeMs = 0L,
            eventHandler = Handler(Looper.getMainLooper()),
            eventListener = null,
        )

        assertNotNull("FFmpeg video renderer must be constructible", renderer)
        assertTrue("Unexpected renderer: ${renderer?.name}", renderer?.name?.contains("ffmpeg", ignoreCase = true) == true)
    }
}
