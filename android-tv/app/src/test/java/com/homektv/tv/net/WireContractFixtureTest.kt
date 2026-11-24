package com.homektv.tv.net

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class WireContractFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesSharedQueueSnapshotAndPlaybackDescriptorFixtures() {
        val fixturesDir = listOf(
            File("contracts/wire-fixtures"),
            File("../contracts/wire-fixtures"),
            File("../../contracts/wire-fixtures"),
        ).first { it.isDirectory }

        val snapshotText = File(fixturesDir, "queue_snapshot_playing.json").readText(Charsets.UTF_8)
        val snapshot = json.decodeFromString(QueueSnapshot.serializer(), snapshotText)
        assertEquals("playing", snapshot.state)
        assertEquals(9001L, snapshot.stateRevision)
        assertNotNull(snapshot.playing)
        assertEquals("mkv", snapshot.playback.format)
        assertEquals("h264", snapshot.playback.videoCodec)
        assertEquals("aac", snapshot.playback.audioCodec)

        val variantsText = File(fixturesDir, "playback_descriptor_variants.json").readText(Charsets.UTF_8)
        val variants = json.decodeFromString(ListSerializer(PlaybackDescriptor.serializer()), variantsText)
        assertEquals(2, variants.size)
        assertEquals("LIVE_TRANSCODE", variants[1].kind)
        assertEquals("rmvb", variants[1].format)
        assertEquals("rv40", variants[1].videoCodec)
        assertEquals("cook", variants[1].audioCodec)
    }
}
