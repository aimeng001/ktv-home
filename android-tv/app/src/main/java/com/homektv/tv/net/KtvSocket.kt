package com.homektv.tv.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import com.homektv.tv.session.ServerRecoveryCoordinator

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
        /** 连接状态变化：true=已连上并收到有效协议事件，false=断开/重连中。 */
        fun onConnectionChanged(connected: Boolean) {}
        /** 当前终端是否拥有播放租约；false 时必须停止本地投影。 */
        fun onPlayerRole(active: Boolean) {}
        /** 房主变更广播；控制器据此更新顶歌/删歌权限。 */
        fun onRoomHostChanged(status: RoomHostStatus) {}
        /** 发现不同 endpoint；仅通知会话所有者，不自动改写当前配置。 */
        fun onRecoveryCandidate(candidate: DiscoveredServer) {}
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
    /** The transport and its durable state belong to this immutable session. */
    private val sessionServer = config.serverForHost(config.serverHost)
    private val sessionHost = sessionServer?.hostPort ?: config.serverHost
    private val sessionPlayerToken = config.playerTokenFor(sessionServer)
    private val sessionUserToken = config.userTokenFor(sessionServer)
    private val playbackBridgeEpoch = if (role == KtvSocketRole.PLAYER) {
        PlaybackSnapshotBridge.beginSession(sessionHost)
    } else null
    private val finishedOutbox = FinishedReportOutbox(
        config.pendingFinishedQueueIds(sessionServer),
        { queueIds -> config.savePendingFinishedQueueIds(queueIds, sessionServer) },
    )
    private val playErrorOutbox = PlaybackErrorOutbox(
        config.pendingPlaybackErrors(sessionServer),
        { errors -> config.savePendingPlaybackErrors(errors, sessionServer) },
    )
    private val recoveryPolicy = EndpointRecoveryPolicy()
    private val recoveryCoordinator = ServerRecoveryCoordinator(
        currentServer = sessionServer,
        onCandidate = { candidate ->
            main.post {
                if (!closed) listener.onRecoveryCandidate(candidate)
            }
        },
    )
    @Volatile
    private var discoveryInFlight = false
    private val recoverySupervisor = SupervisorJob()
    private val recoveryScope = CoroutineScope(recoverySupervisor + Dispatchers.IO)

    // 15s 应用层心跳
    private val heartbeat = object : Runnable {
        override fun run() {
            val generation = if (role == KtvSocketRole.PLAYER) leaseGate.activeGeneration() else null
            if (role == KtvSocketRole.PLAYER) {
                if (generation == null) listener.onPlayerRole(false)
                else {
                    flushFinishedReports(generation)
                    flushPlaybackErrors(generation)
                }
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
        recoverySupervisor.cancel()
        discoveryInFlight = false
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
        when (finishedOutbox.enqueue(id)) {
            FinishedReportOutbox.EnqueueResult.ADDED,
            FinishedReportOutbox.EnqueueResult.DUPLICATE -> Unit
            FinishedReportOutbox.EnqueueResult.INVALID ->
                Log.w(TAG, "finishedOutbox rejected invalid queueId=$id")
            FinishedReportOutbox.EnqueueResult.FULL -> {
                Log.e(TAG, "finishedOutbox is full; preserving existing reports, queueId=$id")
                listener.onToast("播放完成待上报队列已满，已暂停新增记录")
            }
        }
        leaseGate.activeGeneration()?.let(::flushFinishedReports)
        leaseGate.activeGeneration()?.let(::flushPlaybackErrors)
    }

    /** 播放文件不可读时上报，服务端会标记当前项异常并推进队列。 */
    fun sendPlayError(message: String, fileId: Long? = null, queueId: Long? = null) {
        if (role != KtvSocketRole.PLAYER) return
        val id = queueId?.takeIf { it > 0 } ?: return
        when (playErrorOutbox.enqueue(PendingPlaybackError(id, fileId, message))) {
            PlaybackErrorOutbox.EnqueueResult.ADDED,
            PlaybackErrorOutbox.EnqueueResult.DUPLICATE -> Unit
            PlaybackErrorOutbox.EnqueueResult.INVALID -> Log.w(TAG, "playErrorOutbox rejected invalid queueId=$id")
            PlaybackErrorOutbox.EnqueueResult.FULL -> {
                Log.e(TAG, "playErrorOutbox is full; preserving existing reports, queueId=$id")
                listener.onToast("播放失败待上报队列已满，已暂停新增记录")
            }
        }
        leaseGate.activeGeneration()?.let(::flushPlaybackErrors)
    }

    private fun openSocket() {
        if (closed) return
        val epoch = socketEpoch.begin()
        syncChunkAssembler.reset()
        syncReadyGate.onOpen()
        val url = when (role) {
            KtvSocketRole.PLAYER -> config.wsUrlFor(sessionServer, sessionPlayerToken)
            KtvSocketRole.CONTROLLER -> config.controllerWsUrlFor(sessionServer, sessionUserToken)
        }
        Log.d(TAG, "connecting ${safeWebSocketLogTarget(sessionHost)}")
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
        if (recoveryPolicy.onFailure(SystemClock.elapsedRealtime()) && !discoveryInFlight) {
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
                markProtocolAccepted()
                listener.onPlayerRole(leaseGate.activeGeneration() != null)
                if (ConnectionEventPolicy.establishesConnection(type)) {
                    listener.onConnectionChanged(true)
                }
                leaseGate.activeGeneration()?.let(::flushFinishedReports)
        leaseGate.activeGeneration()?.let(::flushPlaybackErrors)
            }
            "playback_report_ack" -> {
                if (role != KtvSocketRole.PLAYER) return
                val ack = payload as? JsonObject ?: return
                val queueId = runCatching { ack["queue_id"]?.jsonPrimitive?.long }.getOrNull() ?: return
                val status = ack["status"]?.jsonPrimitive?.contentOrNullSafe() ?: return
                val reportType = ack["report_type"]?.jsonPrimitive?.contentOrNullSafe()
                when (reportType) {
                    "play_error" -> playErrorOutbox.acknowledge(queueId, status)
                    "finished" -> finishedOutbox.acknowledge(queueId, status)
                    else -> {
                        // Legacy servers did not identify the report type.
                        finishedOutbox.acknowledge(queueId, status)
                        playErrorOutbox.acknowledge(queueId, status)
                    }
                }
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
                markProtocolAccepted()
                if (SnapshotEventPolicy.isCompleteSnapshot(assembled.eventType) && syncReadyGate.markReady()) {
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
                markProtocolAccepted()
                if (SnapshotEventPolicy.isCompleteSnapshot(type) && syncReadyGate.markReady()) {
                    listener.onConnectionChanged(true)
                    publishPlaybackBridgeConnection(true)
                }
            }
            else -> Log.d(TAG, "unhandled event: $type")
        }
    }

    private fun markProtocolAccepted() {
        recoveryPolicy.onSuccess()
        attempt = 0
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else content.ifEmpty { null }

    private fun flushFinishedReports(generation: Long) {
        val socket = ws ?: return
        finishedOutbox.flush(generation) { queueId, currentGeneration ->
            socket.send(buildFinishedMessage(queueId, currentGeneration))
        }
    }

    private fun flushPlaybackErrors(generation: Long) {
        val socket = ws ?: return
        playErrorOutbox.flush(generation) { error, currentGeneration ->
            socket.send(buildPlayErrorMessage(error.message, error.fileId, error.queueId, currentGeneration))
        }
    }

    private fun publishPlaybackBridge(event: String, snapshot: QueueSnapshot) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publish(sessionHost, bridgeEpoch, event, snapshot)
    }

    private fun publishPlaybackBridgeConnection(connected: Boolean) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publishConnection(sessionHost, bridgeEpoch, connected)
    }

    private fun publishPlaybackBridgeRoomHost(status: RoomHostStatus) {
        val bridgeEpoch = playbackBridgeEpoch ?: return
        PlaybackSnapshotBridge.publishRoomHost(sessionHost, bridgeEpoch, status)
    }

    private fun triggerBackgroundRecovery() {
        val expectedInstanceId = sessionServer?.instanceId
            ?.let(DiscoveryProtocol::normalizeInstanceId)
            ?: return
        discoveryInFlight = true
        recoveryScope.launch {
            try {
                LanDiscovery(config.appContext).use { discovery ->
                    val targets = discovery.discoverPreferred(expectedInstanceId)
                    if (!closed) {
                        recoveryCoordinator.reportDiscovered(targets)
                    }
                }
            } catch (cancelled: CancellationException) {
                // Socket close owns cancellation; no late candidate is delivered.
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
