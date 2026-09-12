package com.homektv.tv.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * TV 端 WebSocket 客户端（P1.27 + P1.16 TV 侧）。
 * - 连接 /ws?client_type=tv，收到 sync_full 及各增量事件（payload 为完整快照）
 * - 15s 心跳 ping；断线指数退避重连 1/2/5/10s 封顶（详设§4.1）
 * - 上行 progress(1s) 与 finished（P1.15/P1.28 播放引擎调用）
 *
 * 所有回调在主线程分发，便于直接更新 UI。
 *
 * All callbacks are dispatched on the main thread so the UI can be updated directly.
 */
class KtvSocket(
    private val config: AppConfig,
    private val listener: Listener,
    private val role: KtvSocketRole = KtvSocketRole.PLAYER,
) {
    interface Listener {
        /** 收到 sync_full 或任一携带完整快照的广播事件（now_playing/queue_updated/…）。 */
        fun onSnapshot(event: String, snapshot: QueueSnapshot)
        /** 收到 progress 转发（一般 TV 自己就是源，这里主要用于多 TV 场景，可忽略）。 */
        fun onProgress(positionMs: Long) {}
        /** 氛围音效（P3.1 TV 混播）。 */
        fun onEffect(effectId: String) {}
        /** toast 提示。 */
        fun onToast(text: String) {}
        /** 连接状态变化：true=已连上并完成一次同步，false=断开/重连中。 */
        fun onConnectionChanged(connected: Boolean) {}
        /** 当前终端是否拥有播放租约；false 时必须停止本地投影。 */
        fun onPlayerRole(active: Boolean) {}
        /** 房主变更广播；控制器据此更新顶歌/删歌权限。 */
        fun onRoomHostChanged(status: RoomHostStatus) {}
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val main = Handler(Looper.getMainLooper())
    private val inboundQueue = InboundDispatchQueue()
    private val inboundDrainLock = Any()
    private var inboundDrainScheduled = false
    private val inboundDrain = Runnable { drainInbound() }

    private var http = newHttpClient()
    private var httpClosed = false

    private var ws: WebSocket? = null
    @Volatile
    private var closed = false
    private var attempt = 0
    private val reconnectGate = ReconnectGate()
    private var reconnectRunnable: Runnable? = null
    private val leaseGate = ActivePlayerLeaseGate()
    private val syncReadyGate = SyncReadyGate()
    private val syncChunkAssembler = SyncChunkAssembler()
    private val socketEpoch = SocketEpochGate()
    private val playbackBridgeEpoch = if (role == KtvSocketRole.PLAYER) {
        PlaybackSnapshotBridge.beginSession(config.serverHost)
    } else null
    private val reportServerHost = config.serverHost
    private val finishedOutbox = FinishedReportOutbox(
        config.pendingFinishedQueueIds(reportServerHost),
        { queueIds -> config.savePendingFinishedQueueIds(queueIds, reportServerHost) },
    )
    private val recoveryPolicy = EndpointRecoveryPolicy()
    @Volatile
    private var discoveryInFlight = false

    // 15s 应用层心跳
    private val heartbeat = object : Runnable {
        override fun run() {
            val generation = if (role == KtvSocketRole.PLAYER) leaseGate.activeGeneration() else null
            if (role == KtvSocketRole.PLAYER) {
                if (generation == null) listener.onPlayerRole(false)
                else flushFinishedReports(generation)
            }
            ws?.send("""{"type":"ping","payload":{"generation":${generation ?: "null"}}}""")
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun connect() {
        main.removeCallbacks(inboundDrain)
        synchronized(inboundDrainLock) {
            inboundQueue.reopen()
            inboundDrainScheduled = false
        }
        if (httpClosed) {
            http = newHttpClient()
            httpClosed = false
        }
        closed = false
        cancelReconnect()
        reconnectGate.markRun()
        socketEpoch.invalidate()
        syncChunkAssembler.reset()
        syncReadyGate.onOpen()
        openSocket()
    }

    fun close() {
        closed = true
        cancelReconnect()
        reconnectGate.markRun()
        socketEpoch.invalidate()
        syncChunkAssembler.reset()
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(inboundDrain)
        synchronized(inboundDrainLock) {
            inboundQueue.close()
            inboundDrainScheduled = false
        }
        finishedOutbox.onDisconnected()
        ws?.close(1000, "client closing")
        ws = null
        http.closeResources()
        httpClosed = true
        syncReadyGate.onDisconnect()
        leaseGate.disconnect()
        listener.onPlayerRole(false)
        publishPlaybackBridgeConnection(false)
    }

    /** 上行播放进度（P1.28 播放引擎每 1s 调用）。 */
    fun sendProgress(positionMs: Long, queueId: Long? = null) {
        if (role != KtvSocketRole.PLAYER) return
        val generation = leaseGate.activeGeneration() ?: return
        val payload = buildJsonObject {
            put("position_ms", positionMs)
            queueId?.let { put("queue_id", it) }
            put("generation", generation)
        }
        ws?.send(buildSocketMessage("progress", payload))
    }

    /** 上行播放完成（P1.33 自动连播）。 */
    fun sendFinished(queueId: Long? = null) {
        if (role != KtvSocketRole.PLAYER) return
        val id = queueId?.takeIf { it > 0 } ?: return
        finishedOutbox.enqueue(id)
        leaseGate.activeGeneration()?.let(::flushFinishedReports)
    }

    /** 播放文件不可读时上报，服务端会标记当前项异常并推进队列。 */
    fun sendPlayError(message: String, fileId: Long? = null, queueId: Long? = null) {
        if (role != KtvSocketRole.PLAYER) return
        val generation = leaseGate.activeGeneration() ?: return
        ws?.send(buildPlayErrorMessage(message, fileId, queueId, generation))
    }

    private fun openSocket() {
        if (closed) return
        val epoch = socketEpoch.begin()
        syncChunkAssembler.reset()
        syncReadyGate.onOpen()
        val url = when (role) {
            KtvSocketRole.PLAYER -> config.wsUrl(config.playerToken)
            KtvSocketRole.CONTROLLER -> config.controllerWsUrl(config.userToken)
        }
        Log.d(TAG, "connecting ${safeWebSocketLogTarget(config.serverHost)}")
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, socketListener(epoch))
    }

    private fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 长连接不设读超时
        .pingInterval(0, TimeUnit.MILLISECONDS)  // 用应用层 ping，不用 OkHttp 帧 ping
        .build()

    private fun scheduleReconnect(epoch: Long) {
        if (closed || !socketEpoch.isCurrent(epoch) || !reconnectGate.trySchedule()) return
        if (recoveryPolicy.onFailure() && !discoveryInFlight) {
            triggerBackgroundRecovery()
        }
        val delay = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)]
        attempt++
        Log.d(TAG, "reconnect in ${delay}ms (attempt $attempt)")
        val runnable = Runnable {
            reconnectRunnable = null
            reconnectGate.markRun()
            if (!closed && socketEpoch.isCurrent(epoch)) openSocket()
        }
        reconnectRunnable = runnable
        main.postDelayed(runnable, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(main::removeCallbacks)
        reconnectRunnable = null
    }

    private fun socketListener(epoch: Long) = object : WebSocketListener() {
        private fun isCurrent(webSocket: WebSocket): Boolean =
            !closed && socketEpoch.isCurrent(epoch) && ws === webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            recoveryPolicy.onSuccess()
            attempt = 0
            main.post {
                if (!isCurrent(webSocket)) return@post
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, HEARTBEAT_MS)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val wireBytes = Utf8Budget.countAtMost(text, MAX_MESSAGE_BYTES)
            if (wireBytes == null) {
                Log.w(TAG, "ws message too large: UTF-8 bytes exceed $MAX_MESSAGE_BYTES")
                webSocket.close(1009, "message too large")
                return
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = root["type"]?.jsonPrimitive?.contentOrNullSafe() ?: return
            val payload = root["payload"]

            if (!enqueueInbound(epoch, webSocket, type, payload, wireBytes)
                    && isCurrent(webSocket)) {
                Log.w(TAG, "ws inbound queue full; reconnecting")
                webSocket.close(1009, "inbound queue full")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            Log.w(TAG, "ws failure: ${t.message}")
            ws = null
            inboundQueue.clearPending()
            syncChunkAssembler.reset()
            main.post {
                if (closed || !socketEpoch.isCurrent(epoch) || ws != null) return@post
                main.removeCallbacks(heartbeat)
                syncReadyGate.onDisconnect()
                finishedOutbox.onDisconnected()
                leaseGate.disconnect()
                listener.onPlayerRole(false)
                listener.onConnectionChanged(false)
                publishPlaybackBridgeConnection(false)
            }
            scheduleReconnect(epoch)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            ws = null
            inboundQueue.clearPending()
            syncChunkAssembler.reset()
            main.post {
                if (closed || !socketEpoch.isCurrent(epoch) || ws != null) return@post
                main.removeCallbacks(heartbeat)
                syncReadyGate.onDisconnect()
                finishedOutbox.onDisconnected()
                leaseGate.disconnect()
                listener.onPlayerRole(false)
                listener.onConnectionChanged(false)
                publishPlaybackBridgeConnection(false)
            }
            scheduleReconnect(epoch)
        }
    }

    private fun enqueueInbound(
        epoch: Long,
        source: WebSocket,
        type: String,
        payload: kotlinx.serialization.json.JsonElement?,
        wireBytes: Int,
    ): Boolean {
        var accepted = false
        var schedule = false
        synchronized(inboundDrainLock) {
            if (!closed && socketEpoch.isCurrent(epoch)) {
                accepted = inboundQueue.offer(InboundEvent(epoch, source, type, payload, wireBytes))
                if (accepted && !inboundDrainScheduled) {
                    inboundDrainScheduled = true
                    schedule = true
                }
            }
        }
        if (schedule) main.post(inboundDrain)
        return accepted
    }

    private fun drainInbound() {
        var processed = 0
        while (processed++ < MAX_EVENTS_PER_DRAIN) {
            val event = inboundQueue.poll() ?: run {
                finishInboundDrain()
                return
            }
            if (!closed && socketEpoch.isCurrent(event.epoch)
                && (event.source == null || ws === event.source)
            ) {
                dispatch(event.type, event.payload, event.wireBytes)
            }
        }
        finishInboundDrain()
    }

    private fun finishInboundDrain() {
        var schedule = false
        synchronized(inboundDrainLock) {
            if (closed || inboundQueue.isEmpty()) {
                inboundDrainScheduled = false
            } else {
                schedule = true
            }
        }
        if (schedule) main.post(inboundDrain)
    }

    private fun dispatch(
        type: String,
        payload: kotlinx.serialization.json.JsonElement?,
        wireBytes: Int,
    ) {
        when (type) {
            "player_role", "pong" -> {
                if (role != KtvSocketRole.PLAYER) return
                val assignment = payload as? JsonObject ?: return
                val role = assignment["role"]?.jsonPrimitive?.contentOrNullSafe() ?: return
                val generation = runCatching { assignment["generation"]?.jsonPrimitive?.long }.getOrNull() ?: return
                val leaseMs = runCatching { assignment["lease_ms"]?.jsonPrimitive?.long }.getOrNull() ?: return
                leaseGate.apply(role, generation, leaseMs)
                listener.onPlayerRole(leaseGate.activeGeneration() != null)
                leaseGate.activeGeneration()?.let(::flushFinishedReports)
            }
            "playback_report_ack" -> {
                if (role != KtvSocketRole.PLAYER) return
                val ack = payload as? JsonObject ?: return
                val queueId = runCatching { ack["queue_id"]?.jsonPrimitive?.long }.getOrNull() ?: return
                val status = ack["status"]?.jsonPrimitive?.contentOrNullSafe() ?: return
                finishedOutbox.acknowledge(queueId, status)
            }
            "progress" -> {
                listener.onProgress(parseProgressPosition(payload))
            }
            "effect_play" -> {
                listener.onEffect(parseTextPayload(payload, "effect_id"))
            }
            "toast" -> {
                listener.onToast(parseTextPayload(payload, "text"))
            }
            "room_host_changed" -> {
                val status = payload?.let {
                    runCatching { json.decodeFromJsonElement(RoomHostStatus.serializer(), it) }.getOrNull()
                } ?: return
                publishPlaybackBridgeRoomHost(status)
                listener.onRoomHostChanged(status)
            }
            "snapshot_chunk" -> {
                val chunk = payload?.let {
                    runCatching {
                        json.decodeFromJsonElement(QueueSnapshotChunk.serializer(), it)
                    }.getOrNull()
                } ?: return
                val assembled = syncChunkAssembler.accept(chunk, wireBytes) ?: return
                publishPlaybackBridge(assembled.eventType, assembled.snapshot)
                listener.onSnapshot(assembled.eventType, assembled.snapshot)
                if (assembled.eventType == "sync_full" && syncReadyGate.markReady()) {
                    listener.onConnectionChanged(true)
                    publishPlaybackBridgeConnection(true)
                }
            }
            // 以下事件 payload 均为完整快照
            "sync_full", "queue_updated", "now_playing", "player_state", "playback_restarted", "playback_seeked",
            "volume_changed", "vocal_changed" -> {
                syncChunkAssembler.reset()
                val snap = payload?.let {
                    runCatching { json.decodeFromJsonElement(QueueSnapshot.serializer(), it) }.getOrNull()
                } ?: return
                publishPlaybackBridge(type, snap)
                listener.onSnapshot(type, snap)
                if (type == "sync_full" && syncReadyGate.markReady()) {
                    listener.onConnectionChanged(true)
                    publishPlaybackBridgeConnection(true)
                }
            }
            else -> Log.d(TAG, "unhandled event: $type")
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else content.ifEmpty { null }

    private fun flushFinishedReports(generation: Long) {
        val socket = ws ?: return
        finishedOutbox.flush(generation) { queueId, currentGeneration ->
            socket.send(buildFinishedMessage(queueId, currentGeneration))
        }
    }

    private fun publishPlaybackBridge(event: String, snapshot: QueueSnapshot) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publish(config.serverHost, bridgeEpoch, event, snapshot)
    }

    private fun publishPlaybackBridgeConnection(connected: Boolean) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publishConnection(config.serverHost, bridgeEpoch, connected)
    }

    private fun publishPlaybackBridgeRoomHost(status: RoomHostStatus) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publishRoomHost(config.serverHost, bridgeEpoch, status)
    }

    private fun triggerBackgroundRecovery() {
        discoveryInFlight = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val discovery = LanDiscovery(config.appContext)
                val servers = discovery.discoverAll()
                discovery.close()
                if (!closed && recoveryPolicy.canAutoMigrate(servers.size)) {
                    val target = servers.first()
                    if (target.hostPort != config.serverHost) {
                        Log.i(TAG, "auto-migrating to newly discovered server ${target.hostPort}")
                        config.rememberServer(SavedServer(target.hostPort, target.name))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "background discovery failed: ${e.message}")
            } finally {
                discoveryInFlight = false
            }
        }
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 1_048_576
        private const val MAX_EVENTS_PER_DRAIN = 32
        private const val TAG = "KtvSocket"
        private const val HEARTBEAT_MS = 15_000L   // TV 15s 心跳（详设§4.1）
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000) // 指数退避封顶 10s
    }
}

internal fun buildSocketMessage(
    type: String,
    payload: kotlinx.serialization.json.JsonObject,
): String = buildJsonObject {
    put("type", type)
    put("payload", payload)
}.toString()

internal fun buildPlayErrorMessage(
    message: String,
    fileId: Long?,
    queueId: Long?,
    generation: Long,
): String = buildSocketMessage(
    "play_error",
    buildJsonObject {
        put("message", message)
        fileId?.let { put("file_id", it) }
        queueId?.let { put("queue_id", it) }
        put("generation", generation)
    },
)

internal fun buildFinishedMessage(queueId: Long, generation: Long): String = buildSocketMessage(
    "finished",
    buildJsonObject {
        put("queue_id", queueId)
        put("generation", generation)
    },
)

/** Keeps client identity query parameters out of connection logs. */
internal fun safeWebSocketLogTarget(serverHost: String?): String =
    serverHost?.trim()?.takeIf { it.isNotEmpty() }?.let { "ws://$it/ws" }
        ?: "ws://<unconfigured>/ws"

internal fun parseProgressPosition(payload: JsonElement?): Long = runCatching {
    (payload as? JsonObject)?.get("position_ms")?.jsonPrimitive?.long
}.getOrNull()?.coerceAtLeast(0L) ?: 0L

internal fun parseTextPayload(payload: JsonElement?, field: String): String = runCatching {
    (payload as? JsonObject)?.get(field)?.jsonPrimitive?.contentOrNull
}.getOrNull().orEmpty()
