package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.FileSource
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLoadProjectionTest {
    @Test
    fun load_completion_uses_latest_state_received_during_metadata_load() {
        val state = DesiredPlaybackState()
        val projection = PlaybackLoadProjection(state)

        state.update(snapshot(state = "playing", volume = 80, vocalMode = "accompaniment"))
        state.update(
            snapshot(
                state = "paused",
                volume = 22,
                muted = true,
                vocalMode = "original",
                positionMs = 4_321,
                seekSequence = 7,
            ),
        )

        val command = projection.commandForLoadedFile(
            queueId = 10L,
            fileId = 20L,
            streamUrl = "http://server/api/stream/20",
            accompanimentTrackIndex = 1,
            audioTrackCount = 2,
            audioLayout = AudioLayout(layout = "DUAL_TRACK"),
        )

        assertTrue(command != null)
        assertEquals("paused", command?.state)
        assertEquals(22, command?.volume)
        assertTrue(command?.muted == true)
        assertEquals("original", command?.vocalMode)
        assertEquals(4_321L, command?.positionMs)
        assertEquals(7L, command?.seekSequence)
        assertFalse(command?.playWhenReady == true)
    }

    @Test
    fun live_recovery_uses_player_position_unless_a_newer_seek_or_pause_arrives() {
        val state = DesiredPlaybackState()
        val projection = PlaybackLoadProjection(state)
        state.update(snapshot(state = "playing", positionMs = 1_000L, seekSequence = 3L))
        val recovery = PlaybackReplacementRequest(
            queueId = 10L,
            songId = 100L,
            forceTranscode = true,
            recoveryPositionMs = 65_250L,
            recoverySeekSequence = 3L,
            recoveryPlayWhenReady = false,
            stateAtFailure = "playing",
        )

        val recovered = projection.commandForLoadedFile(
            queueId = 10L,
            fileId = 20L,
            streamUrl = "http://server/api/stream/20?transcode=true&start=1.000",
            accompanimentTrackIndex = 1,
            audioTrackCount = 2,
            audioLayout = AudioLayout(layout = "DUAL_TRACK"),
            recovery = recovery,
        )

        assertEquals(65_250L, recovered?.positionMs)
        assertEquals("paused", recovered?.state)
        assertEquals("http://server/api/stream/20?transcode=true&start=65.250", recovered?.streamUrl)

        state.update(snapshot(state = "paused", positionMs = 91_125L, seekSequence = 4L))
        val afterNewerControl = projection.commandForLoadedFile(
            queueId = 10L,
            fileId = 20L,
            streamUrl = "http://server/api/stream/20?transcode=true&start=1.000",
            accompanimentTrackIndex = 1,
            audioTrackCount = 2,
            audioLayout = AudioLayout(layout = "DUAL_TRACK"),
            recovery = recovery,
        )
        assertEquals(91_125L, afterNewerControl?.positionMs)
        assertEquals("paused", afterNewerControl?.state)
        assertEquals("http://server/api/stream/20?transcode=true&start=91.125", afterNewerControl?.streamUrl)
    }

    @Test
    fun load_completion_is_discarded_when_latest_snapshot_no_longer_has_the_queue() {
        val state = DesiredPlaybackState()
        val projection = PlaybackLoadProjection(state)

        state.update(snapshot(state = "idle", playing = null))

        assertNull(
            projection.commandForLoadedFile(
                queueId = 10L,
                fileId = 20L,
                streamUrl = "http://server/api/stream/20",
                accompanimentTrackIndex = null,
                audioTrackCount = 1,
                audioLayout = AudioLayout.normalStereo(),
            ),
        )
    }

    private fun snapshot(
        state: String,
        volume: Int = 60,
        muted: Boolean = false,
        vocalMode: String = "accompaniment",
        positionMs: Long = 0,
        seekSequence: Long = 0,
        playing: NowPlaying? = NowPlaying(10L, SongDto(100L, "Song", "Artist"), null),
    ) = QueueSnapshot(
        playing = playing,
        state = state,
        volume = volume,
        muted = muted,
        vocalMode = vocalMode,
        positionMs = positionMs,
        seekSequence = seekSequence,
    )
}
