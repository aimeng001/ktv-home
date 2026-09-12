package com.homektv.tv.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped, read-only bridge from the combined-mode player socket to a
 * controller surface. It retains only the latest bounded server snapshot and
 * never owns a player lease or sends a command.
 */
data class RealtimeEnvelope(
    val serverHost: String,
    val epoch: Long,
    val connected: Boolean,
    val event: String? = null,
    val snapshot: QueueSnapshot? = null,
    val roomHost: RoomHostStatus? = null,
)

object PlaybackSnapshotBridge {
    private val lock = Any()
    private val _events = MutableStateFlow<RealtimeEnvelope?>(null)
    val events: StateFlow<RealtimeEnvelope?> = _events.asStateFlow()
    val state: StateFlow<RealtimeEnvelope?> = events
    private val activeEpochByServer = object : LinkedHashMap<String, Long>(MAX_SERVER_SESSIONS + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_SERVER_SESSIONS
    }
    private var nextEpoch = 0L

    /** Starts a new process-local session and immediately exposes it as offline. */
    fun beginSession(serverHost: String?): Long {
        val key = serverHost.orEmpty()
        synchronized(lock) {
            val epoch = ++nextEpoch
            activeEpochByServer[key] = epoch
            publishLocked(RealtimeEnvelope(key, epoch, connected = false))
            return epoch
        }
    }

    fun publishConnection(serverHost: String?, epoch: Long, connected: Boolean) {
        val key = serverHost.orEmpty()
        synchronized(lock) {
            val current = _events.value
            val base = if (current != null && current.epoch == epoch && current.serverHost == key) current
            else RealtimeEnvelope(key, epoch, connected = connected)
            publishLocked(base.copy(connected = connected))
        }
    }

    fun publish(serverHost: String?, epoch: Long, event: String, snapshot: QueueSnapshot) {
        val key = serverHost.orEmpty()
        synchronized(lock) {
            val current = _events.value
            val base = if (current != null && current.epoch == epoch && current.serverHost == key) current
            else RealtimeEnvelope(key, epoch, connected = true)
            publishLocked(base.copy(connected = true, event = event, snapshot = snapshot))
        }
    }

    /** Compatibility overload for isolated tests and older callers. */
    fun publish(serverHost: String?, event: String, snapshot: QueueSnapshot) {
        val key = serverHost.orEmpty()
        val epoch = synchronized(lock) {
            activeEpochByServer[key] ?: (++nextEpoch).also { activeEpochByServer[key] = it }
        }
        publish(key, epoch, event, snapshot)
    }

    fun publishRoomHost(serverHost: String?, epoch: Long, status: RoomHostStatus) {
        val key = serverHost.orEmpty()
        synchronized(lock) {
            val current = _events.value
            val base = if (current != null && current.epoch == epoch && current.serverHost == key) current
            else RealtimeEnvelope(key, epoch, connected = true)
            publishLocked(base.copy(connected = true, event = "room_host_changed", roomHost = status))
        }
    }

    /** Compatibility overload for isolated tests and older callers. */
    fun publishRoomHost(serverHost: String?, status: RoomHostStatus) {
        val key = serverHost.orEmpty()
        val epoch = synchronized(lock) {
            activeEpochByServer[key] ?: (++nextEpoch).also { activeEpochByServer[key] = it }
        }
        publishRoomHost(key, epoch, status)
    }

    private fun publishEnvelope(envelope: RealtimeEnvelope) {
        synchronized(lock) { publishLocked(envelope) }
    }

    private fun publishLocked(envelope: RealtimeEnvelope) {
        val current = _events.value
        if (current == null || envelope.epoch > current.epoch ||
            (envelope.epoch == current.epoch && envelope.serverHost == current.serverHost)
        ) {
            _events.value = envelope
        }
    }

    /** Test-only reset; production lifecycle never clears the latest snapshot. */
    internal fun resetForTests() {
        synchronized(lock) {
            activeEpochByServer.clear()
            nextEpoch = 0L
            _events.value = null
        }
    }

    private const val MAX_SERVER_SESSIONS = 8
}
