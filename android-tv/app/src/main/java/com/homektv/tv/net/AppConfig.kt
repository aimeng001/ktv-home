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

    /** WebSocket 地址：ws://host:port/ws?client_type=tv&client_token=xxx */
    fun wsUrl(clientToken: String): String {
        val encoded = android.net.Uri.encode(clientToken)
        return "ws://${serverHost}/ws?client_type=tv&client_token=$encoded&protocol_version=2&platform=ANDROID_TV"
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
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val MAX_SAVED_SERVERS = 10

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
