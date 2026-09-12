package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class SyncChunkAssemblerTest {
    @Test
    fun snapshot_is_applied_only_after_all_chunks_arrive() {
        val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 4)
        val header = QueueSnapshotHeader(state = "idle", volume = 60)
        val first = chunk("sync-1", 0, 2, false, header, 1L)
        val last = chunk("sync-1", 1, 2, true, header, 2L)

        assertNull(assembler.accept(last))
        val result = assembler.accept(first)

        assertNotNull(result)
        assertEquals("sync_full", result!!.eventType)
        assertEquals(listOf(1L, 2L), result.snapshot.list.map { it.queueId })
    }

    @Test
    fun duplicate_chunk_resets_the_partial_snapshot_and_does_not_apply_it() {
        val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 4)
        val header = QueueSnapshotHeader()
        val first = chunk("sync-2", 0, 2, false, header, 1L)
        val last = chunk("sync-2", 1, 2, true, header, 2L)

        assertNull(assembler.accept(first))
        assertNull(assembler.accept(first))
        assertNull(assembler.accept(last))
    }

    @Test
    fun missing_header_is_rejected_instead_of_using_a_default_snapshot_header() {
        val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 4)
        val invalid = chunk("sync-missing-header", 0, 1, true, null, 1L)

        assertNull(assembler.accept(invalid))
    }

    @Test
    fun missing_header_is_not_silently_defaulted_during_wire_decode() {
        val decoded = Json.decodeFromString<QueueSnapshotChunk>(
            """{"eventType":"sync_full","syncId":"sync-missing-header","index":0,"total":1,"last":true,"entries":[]}"""
        )

        assertNull(decoded.header)
    }

    private fun chunk(
        id: String,
        index: Int,
        total: Int,
        last: Boolean,
        header: QueueSnapshotHeader?,
        queueId: Long,
    ) = QueueSnapshotChunk(
        eventType = "sync_full",
        syncId = id,
        index = index,
        total = total,
        last = last,
        header = header,
        entries = listOf(QueueEntry(queueId = queueId)),
    )
}
