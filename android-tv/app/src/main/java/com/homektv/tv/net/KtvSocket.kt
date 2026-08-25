package com.homektv.tv.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    // 15s 应用层心跳
    private val heartbeat = object : Runnable {
        override fun run() {
            ws?.send("""{"type":"ping"}""")
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun connect() {
        closed = false
        openSocket()
    }

    fun close() {
        closed = true
        main.removeCallbacks(heartbeat)
        ws?.close(1000, "client closing")
        ws = null
    }

    /** 上行播放进度（P1.28 播放引擎每 1s 调用）。 */
    fun sendProgress(positionMs: Long, queueId: Long? = null) {
        val id = queueId?.let { ",\"queue_id\":$it" } ?: ""
        ws?.send("""{"type":"progress","payload":{"position_ms":$positionMs$id}}""")
    }

    /** 上行播放完成（P1.33 自动连播）。 */
    fun sendFinished(queueId: Long? = null) {
        val payload = queueId?.let { ",\"payload\":{\"queue_id\":$it}" } ?: ""
        ws?.send("""{"type":"finished"$payload}""")
    }

    /** 播放文件不可读时上报，服务端会标记当前项异常并推进队列。 */
    fun sendPlayError(message: String, fileId: Long? = null, queueId: Long? = null) {
        val safe = message.replace("\\", "\\\\").replace("\"", "\\\"")
        val id = fileId?.let { ",\"file_id\":$it" } ?: ""
        val queue = queueId?.let { ",\"queue_id\":$it" } ?: ""
        ws?.send("""{"type":"play_error","payload":{"message":"$safe"$id$queue}}""")
    }

    private fun openSocket() {
        if (closed) return
        val url = config.wsUrl(config.clientToken)
        Log.d(TAG, "connecting $url")
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, socketListener)
    }

    private fun scheduleReconnect() {
        if (closed) return
        val delay = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)]
        attempt++
        Log.d(TAG, "reconnect in ${delay}ms (attempt $attempt)")
        main.postDelayed({ openSocket() }, delay)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            main.post {
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, HEARTBEAT_MS)
                listener.onConnectionChanged(true)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = root["type"]?.jsonPrimitive?.contentOrNullSafe() ?: return
            val payload = root["payload"]

            main.post { dispatch(type, payload) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.message}")
            main.post {
                main.removeCallbacks(heartbeat)
                listener.onConnectionChanged(false)
            }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post {
                main.removeCallbacks(heartbeat)
                listener.onConnectionChanged(false)
            }
            scheduleReconnect()
        }
    }

    private fun dispatch(type: String, payload: kotlinx.serialization.json.JsonElement?) {
        when (type) {
            "pong" -> {}
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
