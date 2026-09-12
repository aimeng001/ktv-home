package com.homektv.tv.net

internal data class AssembledSnapshot(
    val eventType: String,
    val snapshot: QueueSnapshot,
)

/**
 * Bounded, atomic assembly of server snapshot chunks. A partial or invalid
 * synchronization is never exposed to the playback layer.
 */
internal class SyncChunkAssembler(
    private val maxChunks: Int = MAX_SYNC_CHUNKS,
    private val maxEntries: Int = MAX_QUEUE_ENTRIES,
    private val maxChunkBytes: Int = MAX_MESSAGE_BYTES,
) {
    private var syncId: String? = null
    private var eventType: String? = null
    private var total = 0
    private var header: QueueSnapshotHeader? = null
    private val chunks = HashMap<Int, List<QueueEntry>>()
    private var entryCount = 0

    init {
        require(maxChunks > 0)
        require(maxEntries > 0)
        require(maxChunkBytes > 0)
    }

    @Synchronized
    fun accept(chunk: QueueSnapshotChunk, wireBytes: Int = 0): AssembledSnapshot? {
        if (wireBytes > maxChunkBytes
            || chunk.eventType !in SNAPSHOT_EVENTS
            || chunk.syncId.isBlank()
            || chunk.total !in 1..maxChunks
            || chunk.index !in 0 until chunk.total
            || chunk.last != (chunk.index == chunk.total - 1)
            || chunk.header == null
            || chunk.entries.size > maxEntries
        ) {
            reset()
            return null
        }

        if (syncId == null) {
            syncId = chunk.syncId
            eventType = chunk.eventType
            total = chunk.total
            header = chunk.header
        } else if (syncId != chunk.syncId) {
            reset()
            syncId = chunk.syncId
            eventType = chunk.eventType
            total = chunk.total
            header = chunk.header
        } else if (eventType != chunk.eventType
            || total != chunk.total
            || header != chunk.header
        ) {
            reset()
            return null
        }

        if (chunks.containsKey(chunk.index)) {
            reset()
            return null
        }
        val nextEntryCount = entryCount + chunk.entries.size
        if (nextEntryCount > maxEntries) {
            reset()
            return null
        }
        chunks[chunk.index] = chunk.entries
        entryCount = nextEntryCount

        if (chunks.size != total) return null
        val snapshotEntries = ArrayList<QueueEntry>(entryCount)
        for (index in 0 until total) {
            val entries = chunks[index] ?: run {
                reset()
                return null
            }
            snapshotEntries.addAll(entries)
        }
        val result = AssembledSnapshot(
            eventType = eventType ?: run {
                reset()
                return null
            },
            snapshot = (header ?: run {
                reset()
                return null
            }).toSnapshot(snapshotEntries),
        )
        reset()
        return result
    }

    @Synchronized
    fun reset() {
        syncId = null
        eventType = null
        total = 0
        header = null
        chunks.clear()
        entryCount = 0
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 1_048_576
        const val MAX_QUEUE_ENTRIES = 1_000
        const val MAX_SYNC_CHUNKS = 64
        val SNAPSHOT_EVENTS = setOf(
            "sync_full",
            "queue_updated",
            "now_playing",
            "player_state",
            "playback_restarted",
            "playback_seeked",
            "volume_changed",
            "vocal_changed",
        )
    }
}
