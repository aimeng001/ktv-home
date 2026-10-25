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

    @Test
    fun constructor_rejects_non_positive_resource_limits() {
        listOf(
            { SyncChunkAssembler(maxChunks = 0) },
            { SyncChunkAssembler(maxEntries = 0) },
            { SyncChunkAssembler(maxChunkBytes = 0) },
        ).forEach { create ->
            try {
                create()
                throw AssertionError("non-positive limits must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected from the constructor's public validation contract.
            }
        }
    }

    @Test
    fun rejectsEachInvalidChunkBoundaryAndResetsAnyPartialAssembly() {
        val header = QueueSnapshotHeader()
        val invalidChunks = listOf(
            chunk("bad-event", 0, 1, true, header, 1L).copy(eventType = "unknown"),
            chunk(" ", 0, 1, true, header, 1L),
            chunk("bad-total-zero", 0, 0, true, header, 1L),
            chunk("bad-total-high", 0, 5, false, header, 1L),
            chunk("bad-index-negative", -1, 1, false, header, 1L),
            chunk("bad-index-high", 1, 1, true, header, 1L),
            chunk("bad-last-flag", 0, 1, false, header, 1L),
            chunk("bad-header", 0, 1, true, null, 1L),
            chunk("bad-entry-limit", 0, 1, true, header, 1L)
                .copy(entries = listOf(QueueEntry(1), QueueEntry(2), QueueEntry(3))),
        )

        invalidChunks.forEach { invalid ->
            val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 2)
            assertNull("invalid chunk must be rejected: ${invalid.syncId}", assembler.accept(invalid))
        }

        val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 2)
        val first = chunk("sync-reset", 0, 2, false, header, 1L)
        assertNull(assembler.accept(first))
        assertNull(assembler.accept(invalidChunks.first()))
        assertNull(assembler.accept(chunk("sync-reset", 1, 2, true, header, 2L)))
    }

    @Test
    fun rejectsChunksOverTheConfiguredWireByteBudget() {
        val assembler = SyncChunkAssembler(maxChunkBytes = 8)

        assertNull(assembler.accept(chunk("oversized-wire", 0, 1, true, QueueSnapshotHeader(), 1L), wireBytes = 9))
    }

    @Test
    fun rejectsInconsistentMetadataAndCombinedEntryOverflow() {
        val header = QueueSnapshotHeader(state = "idle", volume = 60)
        val first = chunk("sync-metadata", 0, 2, false, header, 1L)
        val inconsistentChunks = listOf(
            chunk("sync-metadata", 1, 2, true, header, 2L).copy(eventType = "queue_updated"),
            chunk("sync-metadata", 1, 3, false, header, 2L),
            chunk("sync-metadata", 1, 2, true, header.copy(volume = 30), 2L),
        )
        inconsistentChunks.forEach { inconsistent ->
            val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 4)
            assertNull(assembler.accept(first))
            assertNull(assembler.accept(inconsistent))
        }

        val bounded = SyncChunkAssembler(maxChunks = 4, maxEntries = 2)
        assertNull(
            bounded.accept(
                chunk("sync-entry-overflow", 0, 2, false, header, 1L)
                    .copy(entries = listOf(QueueEntry(1), QueueEntry(2))),
            ),
        )
        assertNull(bounded.accept(chunk("sync-entry-overflow", 1, 2, true, header, 3L)))
    }

    @Test
    fun a_new_sync_replaces_partial_state_and_all_supported_snapshot_events_assemble() {
        val assembler = SyncChunkAssembler(maxChunks = 4, maxEntries = 4)
        assertNull(assembler.accept(chunk("stale-sync", 0, 2, false, QueueSnapshotHeader(), 1L)))

        val replacement = assembler.accept(chunk("new-sync", 0, 1, true, QueueSnapshotHeader(), 2L))
        assertNotNull(replacement)
        assertEquals(listOf(2L), replacement!!.snapshot.list.map { it.queueId })

        val supportedEvents = listOf(
            "sync_full",
            "queue_updated",
            "now_playing",
            "player_state",
            "playback_restarted",
            "playback_seeked",
            "volume_changed",
            "vocal_changed",
        )
        supportedEvents.forEachIndexed { index, event ->
            val assembled = SyncChunkAssembler().accept(
                chunk("event-$index", 0, 1, true, QueueSnapshotHeader(), index.toLong())
                    .copy(eventType = event),
            )
            assertNotNull("supported event should assemble: $event", assembled)
            assertEquals(event, assembled!!.eventType)
        }
    }

    private fun chunk(
        id: String,
        index: Int,
        total: Int,
        last: Boolean,
        header: QueueSnapshotHeader?,
        queueId: Long,
        entries: List<QueueEntry> = listOf(QueueEntry(queueId = queueId)),
    ) = QueueSnapshotChunk(
        eventType = "sync_full",
        syncId = id,
        index = index,
        total = total,
        last = last,
        header = header,
        entries = entries,
    )
}
