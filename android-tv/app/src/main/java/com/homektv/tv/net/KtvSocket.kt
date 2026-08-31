package com.homektv.tv.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val main = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 长连接不设读超时
        .pingInterval(0, TimeUnit.MILLISECONDS)  // 用应用层 ping，不用 OkHttp 帧 ping
        .build()

    private var ws: WebSocket? = null
    private var closed = false
    private var attempt = 0
    private val reconnectGate = ReconnectGate()
    private var reconnectRunnable: Runnable? = null
    private val leaseGate = ActivePlayerLeaseGate()
    private val syncReadyGate = SyncReadyGate()
    private val socketEpoch = SocketEpochGate()

    // 15s 应用层心跳
    private val heartbeat = object : Runnable {
        override fun run() {
            val generation = leaseGate.activeGeneration()
            if (generation == null) listener.onPlayerRole(false)
            ws?.send("""{"type":"ping","payload":{"generation":${generation ?: "null"}}}""")
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun connect() {
        closed = false
        cancelReconnect()
        reconnectGate.markRun()
        socketEpoch.invalidate()
        syncReadyGate.onOpen()
        openSocket()
    }

    fun close() {
        closed = true
        cancelReconnect()
        reconnectGate.markRun()
        socketEpoch.invalidate()
        main.removeCallbacks(heartbeat)
        ws?.close(1000, "client closing")
        ws = null
        syncReadyGate.onDisconnect()
        leaseGate.disconnect()
        listener.onPlayerRole(false)
    }

    /** 上行播放进度（P1.28 播放引擎每 1s 调用）。 */
    fun sendProgress(positionMs: Long, queueId: Long? = null) {
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
        val generation = leaseGate.activeGeneration() ?: return
        val payload = buildJsonObject {
            queueId?.let { put("queue_id", it) }
            put("generation", generation)
        }
        ws?.send(buildSocketMessage("finished", payload))
    }

    /** 播放文件不可读时上报，服务端会标记当前项异常并推进队列。 */
    fun sendPlayError(message: String, fileId: Long? = null, queueId: Long? = null) {
        val generation = leaseGate.activeGeneration() ?: return
        ws?.send(buildPlayErrorMessage(message, fileId, queueId, generation))
    }

    private fun openSocket() {
        if (closed) return
        val epoch = socketEpoch.begin()
        syncReadyGate.onOpen()
        val url = config.wsUrl(config.clientToken)
        Log.d(TAG, "connecting ${safeWebSocketLogTarget(config.serverHost)}")
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, socketListener(epoch))
    }

    private fun scheduleReconnect(epoch: Long) {
        if (closed || !socketEpoch.isCurrent(epoch) || !reconnectGate.trySchedule()) return
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
            attempt = 0
            main.post {
                if (!isCurrent(webSocket)) return@post
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, HEARTBEAT_MS)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = root["type"]?.jsonPrimitive?.contentOrNullSafe() ?: return
            val payload = root["payload"]

            main.post { if (isCurrent(webSocket)) dispatch(type, payload) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            Log.w(TAG, "ws failure: ${t.message}")
            ws = null
            main.post {
                if (closed || !socketEpoch.isCurrent(epoch) || ws != null) return@post
                main.removeCallbacks(heartbeat)
                syncReadyGate.onDisconnect()
                leaseGate.disconnect()
                listener.onPlayerRole(false)
                listener.onConnectionChanged(false)
            }
            scheduleReconnect(epoch)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            ws = null
            main.post {
                if (closed || !socketEpoch.isCurrent(epoch) || ws != null) return@post
                main.removeCallbacks(heartbeat)
                syncReadyGate.onDisconnect()
                leaseGate.disconnect()
                listener.onPlayerRole(false)
                listener.onConnectionChanged(false)
            }
            scheduleReconnect(epoch)
        }
    }

    private fun dispatch(type: String, payload: kotlinx.serialization.json.JsonElement?) {
        when (type) {
            "player_role", "pong" -> {
                val assignment = payload as? JsonObject ?: return
                val role = assignment["role"]?.jsonPrimitive?.contentOrNullSafe() ?: return
                val generation = runCatching { assignment["generation"]?.jsonPrimitive?.long }.getOrNull() ?: return
                val leaseMs = runCatching { assignment["lease_ms"]?.jsonPrimitive?.long }.getOrNull() ?: return
                leaseGate.apply(role, generation, leaseMs)
                listener.onPlayerRole(leaseGate.activeGeneration() != null)
            }
            "progress" -> {
                val pos = (payload as? JsonObject)?.get("position_ms")?.jsonPrimitive?.long ?: 0L
                listener.onProgress(pos)
            }
            "effect_play" -> {
                val id = (payload as? JsonObject)?.get("effect_id")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                listener.onEffect(id)
            }
            "toast" -> {
                val txt = (payload as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                listener.onToast(txt)
            }
            // 以下事件 payload 均为完整快照
            "sync_full", "queue_updated", "now_playing", "player_state", "playback_restarted", "playback_seeked",
            "volume_changed", "vocal_changed" -> {
                val snap = payload?.let {
                    runCatching { json.decodeFromJsonElement(QueueSnapshot.serializer(), it) }.getOrNull()
                } ?: return
                listener.onSnapshot(type, snap)
                if (type == "sync_full" && syncReadyGate.markReady()) {
                    listener.onConnectionChanged(true)
                }
            }
            else -> Log.d(TAG, "unhandled event: $type")
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else content.ifEmpty { null }

    companion object {
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

/** Keeps client identity query parameters out of connection logs. */
internal fun safeWebSocketLogTarget(serverHost: String?): String =
    serverHost?.trim()?.takeIf { it.isNotEmpty() }?.let { "ws://$it/ws" }
        ?: "ws://<unconfigured>/ws"
