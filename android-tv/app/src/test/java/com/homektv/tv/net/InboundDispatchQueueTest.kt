package com.homektv.tv.net

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundDispatchQueueTest {
    @Test
    fun snapshots_are_coalesced_but_control_events_remain_bounded() {
        val queue = InboundDispatchQueue(maxItems = 2, maxBytes = 100)

        assertTrue(queue.offer(event("sync_full", 10)))
        assertTrue(queue.offer(event("toast", 10)))
        assertTrue(queue.offer(event("now_playing", 10)))
        assertFalse(queue.offer(event("pong", 10)))

        assertEquals(2, queue.size())
        assertEquals("toast", queue.poll()?.type)
        assertEquals("now_playing", queue.poll()?.type)
        assertNull(queue.poll())
    }

    @Test
    fun close_clears_pending_events_and_rejects_late_messages() {
        val queue = InboundDispatchQueue()
        assertTrue(queue.offer(event("toast", 10)))

        queue.close()

        assertEquals(0, queue.size())
        assertNull(queue.poll())
        assertFalse(queue.offer(event("toast", 10)))
    }

    @Test
    fun newSyncIdChunkDropsStaleChunksFromPreviousSyncId() {
        val queue = InboundDispatchQueue(maxItems = 5, maxBytes = 100)
        queue.offer(chunkEvent("sync-1", 10))
        queue.offer(chunkEvent("sync-1", 10))
        assertEquals(2, queue.size())

        // Incoming chunk from new syncId drops old chunks
        queue.offer(chunkEvent("sync-2", 10))
        assertEquals(1, queue.size())
        val polled = queue.poll()
        assertEquals("snapshot_chunk", polled?.type)
        assertEquals("sync-2", (polled?.payload as? kotlinx.serialization.json.JsonObject)?.get("syncId")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        })
    }

    @Test
    fun completeSnapshotDropsObsoletePendingChunks() {
        val queue = InboundDispatchQueue(maxItems = 5, maxBytes = 100)
        queue.offer(chunkEvent("sync-1", 10))
        queue.offer(chunkEvent("sync-1", 10))
        assertEquals(2, queue.size())

        // Complete snapshot arrives
        queue.offer(event("sync_full", 20))
        assertEquals(1, queue.size())
        assertEquals("sync_full", queue.poll()?.type)
    }

    private fun chunkEvent(syncId: String, wireBytes: Int) = InboundEvent(
        epoch = 1L,
        type = "snapshot_chunk",
        payload = kotlinx.serialization.json.buildJsonObject {
            put("syncId", kotlinx.serialization.json.JsonPrimitive(syncId))
        },
        wireBytes = wireBytes,
    )

    private fun event(type: String, wireBytes: Int) = InboundEvent(
        epoch = 1L,
        type = type,
        payload = buildJsonObject {},
        wireBytes = wireBytes,
    )
}
