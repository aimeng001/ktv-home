package com.homektv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualEnginePlaybackRouterTest {

    private val router = DualEnginePlaybackRouter()

    @Test
    fun testStandardH264AndHevcSelectsPrimaryMedia3Engine() {
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = "h264", format = "mkv"),
        )
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = "hevc", format = "mp4"),
        )
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = null, format = "mkv"),
        )
    }

    @Test
    fun testRealVideoAndRmvbFormatsSelectFallbackFfmpegEngine() {
        assertEquals(
            PlaybackEngineType.FALLBACK_FFMPEG,
            router.selectEngine(videoCodec = "rv40", format = "mkv"),
        )
        assertEquals(
            PlaybackEngineType.FALLBACK_FFMPEG,
            router.selectEngine(videoCodec = "rv30", format = "mkv"),
        )
        assertEquals(
            PlaybackEngineType.FALLBACK_FFMPEG,
            router.selectEngine(videoCodec = null, format = "rmvb"),
        )
        assertEquals(
            PlaybackEngineType.FALLBACK_FFMPEG,
            router.selectEngine(videoCodec = null, format = "rm"),
        )
    }

    @Test
    fun testMedia3ZeroVideoTrackAnomalyTriggersFallback() {
        // 当文件为有画面的视频（如 720x480 分辨率），但 Media3 解封装器将老格式（如 V_REAL/RVD4）静默过滤掉导致视频轨数为 0 时
        val shouldFallback = router.shouldFallbackOnTracks(
            hasVideoDeclared = true,
            videoTrackCount = 0,
            hasSupportedVideoTrack = false,
        )
        assertTrue("Media3 解析出 0 视频轨且该文件声明有画面时，必须触发软解 Fallback", shouldFallback)
    }

    @Test
    fun testMedia3NormalVideoTrackDoesNotTriggerFallback() {
        val shouldFallback = router.shouldFallbackOnTracks(
            hasVideoDeclared = true,
            videoTrackCount = 1,
            hasSupportedVideoTrack = true,
        )
        assertFalse("正常硬解视频轨无需 Fallback", shouldFallback)
    }

    @Test
    fun testAudioOnlyTrackDoesNotTriggerFallback() {
        val shouldFallback = router.shouldFallbackOnTracks(
            hasVideoDeclared = false,
            videoTrackCount = 0,
            hasSupportedVideoTrack = false,
        )
        assertFalse("纯音频文件不应触发视频 Fallback", shouldFallback)
    }
}
