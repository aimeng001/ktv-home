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
    fun testAllContainersAndCodecsSelectPrimaryMedia3Engine() {
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = "rv40", format = "mkv"),
        )
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = "rv30", format = "mkv"),
        )
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = null, format = "rmvb"),
        )
        assertEquals(
            PlaybackEngineType.PRIMARY_MEDIA3,
            router.selectEngine(videoCodec = null, format = "rm"),
        )
    }

    @Test
    fun testMedia3ZeroVideoTrackAnomalyRequiresLiveDecodeResolution() {
        // A declared video with no Media3-supported video track is a decoder failure,
        // not proof that Android MediaPlayer will render it. Escalate to server decode.
        val shouldResolve = router.shouldResolveMissingOrUnsupportedVideoTracks(
            hasVideoDeclared = true,
            videoTrackCount = 0,
            hasSupportedVideoTrack = false,
        )
        assertTrue("declared video with no supported track must request bounded live transcode", shouldResolve)
    }

    @Test
    fun unsupportedTrackRequestsResolutionButSupportedAndAudioOnlyDoNot() {
        assertTrue(
            router.shouldResolveMissingOrUnsupportedVideoTracks(
                hasVideoDeclared = true,
                videoTrackCount = 1,
                hasSupportedVideoTrack = false,
            ),
        )
        assertFalse(
            router.shouldResolveMissingOrUnsupportedVideoTracks(
                hasVideoDeclared = true,
                videoTrackCount = 1,
                hasSupportedVideoTrack = true,
            ),
        )
        assertFalse(
            router.shouldResolveMissingOrUnsupportedVideoTracks(
                hasVideoDeclared = false,
                videoTrackCount = 0,
                hasSupportedVideoTrack = false,
            ),
        )
    }

    @Test
    fun testMedia3NormalVideoTrackDoesNotTriggerFallback() {
        val shouldFallback = router.shouldResolveMissingOrUnsupportedVideoTracks(
            hasVideoDeclared = true,
            videoTrackCount = 1,
            hasSupportedVideoTrack = true,
        )
        assertFalse("正常硬解视频轨无需 Fallback", shouldFallback)
    }

    @Test
    fun testAudioOnlyTrackDoesNotTriggerFallback() {
        val shouldFallback = router.shouldResolveMissingOrUnsupportedVideoTracks(
            hasVideoDeclared = false,
            videoTrackCount = 0,
            hasSupportedVideoTrack = false,
        )
        assertFalse("纯音频文件不应触发视频 Fallback", shouldFallback)
    }
}

