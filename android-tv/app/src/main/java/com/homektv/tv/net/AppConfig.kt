package com.homektv.tv.net

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * NAS 服务端地址与历史连接持久化。
 *
 * Persists the NAS server address and the history of successful connections.
 */
class AppConfig(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ktv_tv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyServer()
    }

    /** 形如 192.168.1.10:8080 的服务端 host:port（已归一化）。未配置时为 null。 */
    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    val isConfigured: Boolean get() = !serverHost.isNullOrBlank()

    val savedServers: List<SavedServer>
        get() = readSavedServers()

    /**
     * 连接成功后去重置顶，最多保留 10 台设备。
     *
     * Deduplicates and promotes a successful connection, keeping at most 10 devices.
     */
    @Synchronized
    fun rememberServer(server: SavedServer) {
        val hostPort = normalizeHost(server.hostPort) ?: return
        val existing = readSavedServers().firstOrNull { it.hostPort == hostPort }
        val incomingName = server.name.trim()
        val name = when {
            incomingName.isNotEmpty() && incomingName != hostPort -> incomingName
            existing != null -> existing.name
            else -> hostPort
        }
        val updated = buildList {
            add(SavedServer(hostPort, name))
            addAll(readSavedServers().filterNot { it.hostPort == hostPort })
        }.take(MAX_SAVED_SERVERS)
        writeSavedServers(updated)
        serverHost = hostPort
    }

    @Synchronized
    fun removeSavedServer(hostPort: String) {
        writeSavedServers(readSavedServers().filterNot { it.hostPort == hostPort })
    }

    var microphoneMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE_MONITOR, true)
        set(value) = prefs.edit { putBoolean(KEY_MICROPHONE_MONITOR, value) }

    /** 与服务端 KTV_PLAYER_CREDENTIAL 对应的可选电视连接密钥。 */
    var playerCredential: String
        get() = prefs.getString(KEY_PLAYER_CREDENTIAL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_PLAYER_CREDENTIAL, value.trim()) }

    /** Finished queue reports waiting for a server acknowledgement. */
    fun pendingFinishedQueueIds(serverHost: String? = this.serverHost): List<Long> =
        prefs.getString(pendingFinishedKey(serverHost), null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }
            ?.distinct()
            ?.take(MAX_PENDING_FINISHED)
            ?: emptyList()

    /** Persists only valid, bounded queue IDs; the source media is never touched. */
    @Synchronized
    fun savePendingFinishedQueueIds(queueIds: List<Long>, serverHost: String? = this.serverHost) {
        val value = queueIds.asSequence()
            .filter { it > 0 }
            .distinct()
            .take(MAX_PENDING_FINISHED)
            .joinToString(",")
        prefs.edit(commit = true) {
            val key = pendingFinishedKey(serverHost)
            if (value.isEmpty()) remove(key)
            else putString(key, value)
        }
    }

    /** WebSocket 地址：ws://host:port/ws?client_type=tv&client_token=xxx */
    fun wsUrl(clientToken: String): String {
        return buildTvWebSocketUrl(serverHost.orEmpty(), clientToken, playerCredential)
    }

    /** REST/资源基址：http://host:port/api */
    fun apiBase(): String = "http://${serverHost}/api"

    /** H5 点歌地址（用于待机页二维码/明文兜底） */
    fun h5Url(): String = "http://${serverHost}/m"

    /** 稳定的设备 token（首次生成后固定），用于 WS client_token。 */
    val clientToken: String
        get() = prefs.getString(KEY_TOKEN, null) ?: run {
            val t = "tv-" + java.util.UUID.randomUUID().toString().take(8)
            prefs.edit { putString(KEY_TOKEN, t) }
            t
        }

    private fun migrateLegacyServer() {
        if (prefs.contains(KEY_SAVED_SERVERS)) return
        val legacyHost = prefs.getString(KEY_HOST, null)
        val initial = legacyHost?.takeIf { it.isNotBlank() }
            ?.let { listOf(SavedServer(it, it)) }
            .orEmpty()
        writeSavedServers(initial)
    }

    private fun readSavedServers(): List<SavedServer> {
        val raw = prefs.getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedServer.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeSavedServers(servers: List<SavedServer>) {
        val raw = json.encodeToString(ListSerializer(SavedServer.serializer()), servers)
        prefs.edit { putString(KEY_SAVED_SERVERS, raw) }
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_TOKEN = "client_token"
        private const val KEY_MICROPHONE_MONITOR = "microphone_monitor_enabled"
        private const val KEY_PLAYER_CREDENTIAL = "player_credential"
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val KEY_PENDING_FINISHED_PREFIX = "pending_finished_queue_ids_"
        private const val MAX_SAVED_SERVERS = 10
        private const val MAX_PENDING_FINISHED = 100

        private fun pendingFinishedKey(serverHost: String?): String =
            KEY_PENDING_FINISHED_PREFIX + serverHost.orEmpty().trim()

        /**
         * 归一化用户输入：去空格、剥离 http(s):// 前缀与尾部斜杠；
         * 未带端口时补默认 8080；手动输入支持任意有效服务端端口。
         */
        fun normalizeHost(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            s = s.removePrefix("http://").removePrefix("https://")
            s = s.substringBefore("/")        // 去掉路径
            if (s.isEmpty()) return null
            if (!s.contains(":")) s = "$s:8080"
            return s
        }
    }
}

internal fun buildTvWebSocketUrl(serverHost: String, clientToken: String, playerCredential: String?): String {
    val token = encodeQueryComponent(clientToken)
    val credential = playerCredential?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "&player_credential=${encodeQueryComponent(it)}" }
        .orEmpty()
    return "ws://$serverHost/ws?client_type=tv&client_token=$token&protocol_version=2&platform=ANDROID_TV$credential"
}

internal fun encodeQueryComponent(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val safe = unsigned in 'A'.code..'Z'.code || unsigned in 'a'.code..'z'.code ||
            unsigned in '0'.code..'9'.code || unsigned == '-'.code || unsigned == '.'.code ||
            unsigned == '_'.code || unsigned == '~'.code
        if (safe) append(unsigned.toChar())
        else append("%${unsigned.toString(16).uppercase().padStart(2, '0')}")
    }
}
