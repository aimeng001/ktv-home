package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedProtocolFixtureTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun assemblesTheServerSnapshotChunkWithPositionRevisionAndAudioLayout() {
        val wire = fixture("snapshot_chunk.json")
        val envelope = json.parseToJsonElement(wire).jsonObject
        val chunk = json.decodeFromJsonElement(
            QueueSnapshotChunk.serializer(), envelope.getValue("payload"),
        )
        val assembled = SyncChunkAssembler().accept(chunk, wire.toByteArray(Charsets.UTF_8).size)

        assertEquals("snapshot_chunk", envelope.getValue("type").toString().trim('"'))
        assertNotNull(assembled)
        assertEquals("sync_full", assembled!!.eventType)
        assertEquals(4101L, assembled.snapshot.playing?.queueId)
        assertEquals(123456L, assembled.snapshot.positionMs)
        assertEquals(19L, assembled.snapshot.seekSequence)
        assertEquals(86L, assembled.snapshot.stateRevision)
        assertEquals("DUAL_TRACK", assembled.snapshot.audioLayout.layout)
        assertEquals("READY", assembled.snapshot.playback.status)
        assertEquals(7101L, assembled.snapshot.playback.sourceFileId)
        assertEquals(4102L, assembled.snapshot.list.single().queueId)
    }

    @Test
    fun decodesNotReadyRestDetailsAndReversedDualChannelLayout() {
        val detail = json.decodeFromString<SongDetail>(fixture("song_detail_not_ready.json"))

        assertEquals(72L, detail.id)
        assertEquals(false, detail.files.single().ready)
        assertEquals("DUAL_CHANNEL", detail.files.single().audioLayout.layout)
        assertEquals("RIGHT", detail.files.single().audioLayout.originalChannel)
        assertEquals("LEFT", detail.files.single().audioLayout.accompanimentChannel)
    }

    @Test
    fun classifiesTheSharedNotReadyBusinessErrorWithoutChangingItsMeaning() {
        val result = classifyKtvResponse(409, fixture("business_error_song_not_ready.json"))

        assertTrue(result is KtvApiResult.Failure)
        val error = (result as KtvApiResult.Failure).error
        assertEquals(KtvApiErrorKind.BUSINESS, error.kind)
        assertEquals("SONG_NOT_READY", error.code)
        assertEquals("歌曲尚未完成媒体探测", error.message.orEmpty())
        assertEquals(409, error.status)
    }

    private fun fixture(name: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "test-fixtures/ktv-protocol/v1/$name")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Shared protocol fixture not found: $name")
    }
}
