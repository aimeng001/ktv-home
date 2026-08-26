package com.homektv.tv.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun decodesThePlatformNeutralDualChannelLayout() {
        val snapshot = json.decodeFromString<QueueSnapshot>(
            """
            {
              "state": "playing",
              "vocalMode": "original",
              "positionMs": 12345,
              "audioLayout": {
                "layout": "DUAL_CHANNEL",
                "originalChannel": "LEFT",
                "accompanimentChannel": "RIGHT"
              },
              "serverOnlyField": "ignored"
            }
            """.trimIndent(),
        )

        assertEquals("playing", snapshot.state)
        assertEquals(12345L, snapshot.positionMs)
        assertEquals("DUAL_CHANNEL", snapshot.audioLayout.layout)
        assertEquals("LEFT", snapshot.audioLayout.originalChannel)
        assertEquals("RIGHT", snapshot.audioLayout.accompanimentChannel)
    }

    @Test
    fun oldSnapshotsKeepTheDualTrackCompatibilityDefaults() {
        val snapshot = json.decodeFromString<QueueSnapshot>(
            """{"state":"paused","playing":null}""",
        )

        assertEquals("DUAL_TRACK", snapshot.audioLayout.layout)
        assertEquals("LEFT", snapshot.audioLayout.originalChannel)
        assertEquals("RIGHT", snapshot.audioLayout.accompanimentChannel)
        assertEquals(0L, snapshot.positionMs)
        assertNull(snapshot.playing)
    }
}
