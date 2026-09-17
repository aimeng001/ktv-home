package com.homektv.tv.net

import kotlinx.serialization.json.JsonElement
import okhttp3.WebSocket

internal data class InboundEvent(
    val epoch: Long,
    val source: WebSocket? = null,
    val type: String,
    val payload: JsonElement?,
    val wireBytes: Int,
)

/**
 * Bounded hand-off between OkHttp's WebSocket thread and the main thread.
 * Snapshot broadcasts are replaceable; control messages retain FIFO order and
 * are never allowed to grow the queue beyond the item/byte budgets.
 */
internal class InboundDispatchQueue(
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val events = ArrayList<InboundEvent>(maxItems)
    private var queuedBytes = 0
    private var open = true

    init {
        require(maxItems > 0) { "maxItems must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Synchronized
    fun offer(event: InboundEvent): Boolean {
        if (!open || event.wireBytes <= 0 || event.wireBytes > maxBytes) return false

        if (isSnapshotType(event.type)) {
            dropAllChunks()
            val oldIndex = events.indexOfFirst { isSnapshotType(it.type) }
            if (oldIndex >= 0) {
                val old = events.removeAt(oldIndex)
                if (queuedBytes - old.wireBytes + event.wireBytes > maxBytes) {
                    events.add(oldIndex, old)
                    return false
                }
                queuedBytes -= old.wireBytes
            }
        } else if (event.type == "snapshot_chunk") {
            val incomingSyncId = extractSyncId(event.payload)
            if (!incomingSyncId.isNullOrBlank()) {
                dropChunksNotMatching(incomingSyncId)
            }
        } else if (events.size >= maxItems) {
            return false
        }

        if (events.size >= maxItems || queuedBytes + event.wireBytes > maxBytes) {
            return false
        }
        events.add(event)
        queuedBytes += event.wireBytes
        return true
    }

    @Synchronized
    fun poll(): InboundEvent? {
        if (events.isEmpty()) return null
        val event = events.removeAt(0)
        queuedBytes -= event.wireBytes
        return event
    }

    @Synchronized
    fun size(): Int = events.size

    @Synchronized
    fun isEmpty(): Boolean = events.isEmpty()

    @Synchronized
    fun clearPending() {
        events.clear()
        queuedBytes = 0
    }

    @Synchronized
    fun reopen() {
        clearPending()
        open = true
    }

    @Synchronized
    fun close() {
        clearPending()
        open = false
    }

    private fun isSnapshotType(type: String): Boolean = SnapshotEventPolicy.isCompleteSnapshot(type)

    private fun extractSyncId(payload: JsonElement?): String? = runCatching {
        (payload as? kotlinx.serialization.json.JsonObject)?.get("syncId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()

    private fun dropAllChunks() {
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val ev = iterator.next()
            if (ev.type == "snapshot_chunk") {
                queuedBytes -= ev.wireBytes
                iterator.remove()
            }
        }
    }

    private fun dropChunksNotMatching(currentSyncId: String) {
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val ev = iterator.next()
            if (ev.type == "snapshot_chunk") {
                val oldSyncId = extractSyncId(ev.payload)
                if (oldSyncId != null && oldSyncId != currentSyncId) {
                    queuedBytes -= ev.wireBytes
                    iterator.remove()
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ITEMS = 96
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
    }
}
