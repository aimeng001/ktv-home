package com.homektv.tv.net

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.edit
import com.homektv.tv.session.DeviceCapabilities
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceModeRouter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * NAS 服务端地址与历史连接持久化。
 *
 * Persists the NAS server address and the history of successful connections.
 */
class AppConfig(context: Context) {

    val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("ktv_tv", Context.MODE_PRIVATE)
    private val credentialStore = CredentialStore(appContext)
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

    /** Snapshot of the saved server selected by [hostPort]. */
    internal fun serverForHost(hostPort: String? = serverHost): SavedServer? {
        val normalized = hostPort?.let(::normalizeHost) ?: return null
        return readSavedServers().firstOrNull { it.hostPort == normalized }
            ?: SavedServer(normalized, normalized)
    }

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
        // A missing identity is an explicit unknown-server choice (for example
        // a legacy subnet candidate). Never turn it into the identity of the
        // previously saved server at the same host.
        val selectedInstanceId = ServerSessionScope.normalizeInstanceId(server.instanceId)
        val selected = SavedServer(hostPort, name, selectedInstanceId)
        val updated = SavedServerPolicy.merge(readSavedServers(), selected, MAX_SAVED_SERVERS)
        writeSavedServersAndSelect(updated, hostPort)
    }

    @Synchronized
    fun migrateLegacyScope(from: SavedServer, to: SavedServer): Boolean {
        if (from.hostPort != to.hostPort || from.instanceId != null || to.instanceId == null) return false
        // This method is called only after the user explicitly accepted the
        // legacy-to-instance migration dialog. Write and verify the encrypted
        // credential first; clear the old scope only after every new value is
        // present so a failed Keystore operation cannot destroy the old login.
        if (!credentialStore.migrate(from, to)) return false

        val oldUserTokenKey = ServerSessionScope.preferenceKey(KEY_USER_TOKEN_PREFIX, from)
        val newUserTokenKey = ServerSessionScope.preferenceKey(KEY_USER_TOKEN_PREFIX, to)
        val oldUserToken = prefs.getString(oldUserTokenKey, null)

        val oldPlayerTokenKey = ServerSessionScope.preferenceKey(KEY_PLAYER_TOKEN_PREFIX, from)
        val newPlayerTokenKey = ServerSessionScope.preferenceKey(KEY_PLAYER_TOKEN_PREFIX, to)
        val oldPlayerToken = prefs.getString(oldPlayerTokenKey, null)

        val oldNicknameKey = ServerSessionScope.preferenceKey(KEY_NICKNAME_PREFIX, from)
        val newNicknameKey = ServerSessionScope.preferenceKey(KEY_NICKNAME_PREFIX, to)
        val oldNickname = prefs.getString(oldNicknameKey, null)

        val oldModeKey = ServerSessionScope.preferenceKey(KEY_MODE_PREFIX, from)
        val newModeKey = ServerSessionScope.preferenceKey(KEY_MODE_PREFIX, to)
        val oldMode = prefs.getString(oldModeKey, null)
        val oldPendingKey = ServerSessionScope.preferenceKey(KEY_PENDING_FINISHED_PREFIX, from)
        val pendingFrom = pendingFinishedQueueIds(from)
        val pendingTo = pendingFinishedQueueIds(to)
        val oldPlayErrorKey = ServerSessionScope.preferenceKey(KEY_PENDING_PLAYBACK_ERRORS_PREFIX, from)
        val pendingPlayErrorsFrom = pendingPlaybackErrors(from)
        val pendingPlayErrorsTo = pendingPlaybackErrors(to)

        prefs.edit(commit = true) {
            if (!oldUserToken.isNullOrBlank() && prefs.getString(newUserTokenKey, null).isNullOrBlank()) {
                putString(newUserTokenKey, oldUserToken)
            }
            if (!oldPlayerToken.isNullOrBlank() && prefs.getString(newPlayerTokenKey, null).isNullOrBlank()) {
                putString(newPlayerTokenKey, oldPlayerToken)
            }
            if (!oldNickname.isNullOrBlank() && prefs.getString(newNicknameKey, null).isNullOrBlank()) {
                putString(newNicknameKey, oldNickname)
            }
            if (!oldMode.isNullOrBlank() && prefs.getString(newModeKey, null).isNullOrBlank()) {
                putString(newModeKey, oldMode)
            }
        }
        savePendingFinishedQueueIds(PendingFinishedPolicy.merge(pendingTo, pendingFrom), to)
        savePendingPlaybackErrors(PlaybackErrorOutbox.merge(pendingPlayErrorsTo, pendingPlayErrorsFrom), to)
        prefs.edit(commit = true) {
            remove(oldUserTokenKey)
            remove(oldPlayerTokenKey)
            remove(oldNicknameKey)
            remove(oldModeKey)
            remove(oldPendingKey)
            remove(oldPlayErrorKey)
            remove(KEY_TOKEN)
        }
        credentialStore.clear(from)
        return true
    }

    @Synchronized
    fun removeSavedServer(hostPort: String) {
        writeSavedServers(readSavedServers().filterNot { it.hostPort == hostPort })
    }

    @Synchronized
    fun forgetServer(server: SavedServer) {
        val normalizedHost = normalizeHost(server.hostPort) ?: return
        val saved = readSavedServers()
        val removed = saved.filter { it.hostPort == normalizedHost }
        val remaining = saved.filterNot { it.hostPort == normalizedHost }
        val selectedHost = serverHost?.let(::normalizeHost)
        writeSavedServers(remaining)
        val scopes = (removed + server).distinct()
        scopes.forEach(credentialStore::clearAllScopes)
        prefs.edit(commit = true) {
            scopes.forEach { scope ->
                remove(ServerSessionScope.preferenceKey(KEY_USER_TOKEN_PREFIX, scope))
                remove(ServerSessionScope.preferenceKey(KEY_PLAYER_TOKEN_PREFIX, scope))
                remove(ServerSessionScope.preferenceKey(KEY_NICKNAME_PREFIX, scope))
                remove(ServerSessionScope.preferenceKey(KEY_MODE_PREFIX, scope))
                remove(ServerSessionScope.preferenceKey(KEY_PENDING_FINISHED_PREFIX, scope))
                remove(ServerSessionScope.preferenceKey(KEY_PENDING_PLAYBACK_ERRORS_PREFIX, scope))
            }
            if (selectedHost == normalizedHost) {
                remaining.firstOrNull()?.hostPort?.let { host -> putString(KEY_HOST, host) }
                    ?: remove(KEY_HOST)
                remove(KEY_TOKEN)
            }
        }
    }

    var microphoneMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE_MONITOR, true)
        set(value) = prefs.edit { putBoolean(KEY_MICROPHONE_MONITOR, value) }

    /** 与服务端 KTV_PLAYER_CREDENTIAL 对应的可选电视连接密钥。 */
    var playerCredential: String
        get() = credentialStore.read(serverForHost())
        set(value) = credentialStore.write(value, serverForHost())

    internal fun playerCredentialFor(server: SavedServer?): String = credentialStore.read(server)

    /** Finished queue reports waiting for a server acknowledgement. */
    fun pendingFinishedQueueIds(serverHost: String? = this.serverHost): List<Long> =
        pendingFinishedQueueIds(serverForHost(serverHost))

    internal fun pendingFinishedQueueIds(server: SavedServer?): List<Long> {
        val scope = server ?: return emptyList()
        return prefs.getString(ServerSessionScope.preferenceKey(KEY_PENDING_FINISHED_PREFIX, scope), null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }
            ?.let(PendingFinishedPolicy::sanitize)
            ?: emptyList()
    }

    /** Persists only valid, bounded queue IDs; the source media is never touched. */
    @Synchronized
    fun savePendingFinishedQueueIds(queueIds: List<Long>, serverHost: String? = this.serverHost) {
        savePendingFinishedQueueIds(queueIds, serverForHost(serverHost))
    }

    @Synchronized
    internal fun savePendingFinishedQueueIds(queueIds: List<Long>, server: SavedServer?) {
        if (server == null) return
        val value = PendingFinishedPolicy.sanitize(queueIds).joinToString(",")
        prefs.edit(commit = true) {
            val key = ServerSessionScope.preferenceKey(KEY_PENDING_FINISHED_PREFIX, server)
            if (value.isEmpty()) remove(key)
            else {
                putString(key, value)
            }
        }
    }

    /** Play-error reports waiting for a server acknowledgement. */
    internal fun pendingPlaybackErrors(server: SavedServer?): List<PendingPlaybackError> {
        if (server == null) return emptyList()
        val raw = prefs.getString(
            ServerSessionScope.preferenceKey(KEY_PENDING_PLAYBACK_ERRORS_PREFIX, server), null,
        ) ?: return emptyList()
        if (raw.length > MAX_PENDING_PLAYBACK_ERRORS_JSON_CHARS) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PendingPlaybackError.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    internal fun savePendingPlaybackErrors(errors: List<PendingPlaybackError>, server: SavedServer?) {
        if (server == null) return
        val sanitized = PlaybackErrorOutbox.sanitize(errors)
        val key = ServerSessionScope.preferenceKey(KEY_PENDING_PLAYBACK_ERRORS_PREFIX, server)
        val value = json.encodeToString(ListSerializer(PendingPlaybackError.serializer()), sanitized)
        prefs.edit(commit = true) {
            if (sanitized.isEmpty()) remove(key) else putString(key, value)
        }
    }
    /** WebSocket 地址：ws://host:port/ws?client_type=tv&client_token=xxx */
    fun wsUrl(clientToken: String): String {
        return wsUrlFor(serverForHost(), clientToken)
    }

    internal fun wsUrlFor(server: SavedServer?, clientToken: String): String =
        buildTvWebSocketUrl(server?.hostPort.orEmpty(), clientToken, playerCredentialFor(server))

    /** WebSocket 地址 for the controller-compatible V1 session. */
    fun controllerWsUrl(userToken: String = this.userToken): String =
        controllerWsUrlFor(serverForHost(), userToken)

    internal fun controllerWsUrlFor(server: SavedServer?, userToken: String): String =
        buildControllerWebSocketUrl(
            serverHost = server?.hostPort.orEmpty(),
            userToken = userToken,
            platform = controllerPlatform(),
            deviceMode = effectiveMode(server?.hostPort).name,
        )

    /** Explicit form-factor metadata lets the server count phones without
     * conflating them with browser H5 sessions. */
    fun controllerPlatform(): String {
        val configuration = appContext.resources.configuration
        val television = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        if (television) return "ANDROID_TV"
        return if (configuration.smallestScreenWidthDp >= 600) "ANDROID_TABLET" else "ANDROID_PHONE"
    }

    /** REST/资源基址：http://host:port/api */
    fun apiBase(): String = "http://${serverHost}/api"

    /** H5 点歌地址（用于待机页二维码/明文兜底） */
    fun h5Url(): String = "http://${serverHost}/m"

    /**
     * Stable player token for the currently selected server. The old global
     * key is migrated only for the current server so switching servers cannot
     * accidentally reuse the playback identity.
     */
    val playerToken: String
        get() = playerTokenFor(serverForHost())

    internal fun playerTokenFor(server: SavedServer?): String =
        tokenForServer(KEY_PLAYER_TOKEN_PREFIX, "tv-", server, migrateLegacy = true)

    /** Backwards-compatible name used by the existing TV playback code. */
    val clientToken: String
        get() = playerToken

    /** Stable controller identity scoped to the selected server. */
    val userToken: String
        get() = userTokenFor(serverForHost())

    fun userTokenFor(hostPort: String?): String = userTokenFor(serverForHost(hostPort))

    internal fun userTokenFor(server: SavedServer?): String =
        tokenForServer(KEY_USER_TOKEN_PREFIX, "user-", server)

    /** Nicknames are server-scoped and never sent as part of the player WS. */
    fun nicknameFor(hostPort: String? = serverHost): String =
        readScopedString(KEY_NICKNAME_PREFIX, serverForHost(hostPort)).orEmpty()

    fun saveNickname(nickname: String, hostPort: String? = serverHost) {
        writeScopedString(KEY_NICKNAME_PREFIX, serverForHost(hostPort), nickname.trim().take(MAX_NICKNAME_LENGTH))
    }

    /** The first-run recommendation; a saved choice always wins per server. */
    val recommendedMode: DeviceMode
        get() = DeviceModeRouter.recommend(
            DeviceCapabilities(
                isTelevision = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    (appContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                    Configuration.UI_MODE_TYPE_TELEVISION,
                hasTouchscreen = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            ),
        )

    fun modeFor(hostPort: String? = serverHost): DeviceMode? =
        readScopedString(KEY_MODE_PREFIX, serverForHost(hostPort))
            ?.let { value -> runCatching { DeviceMode.valueOf(value) }.getOrNull() }

    fun effectiveMode(hostPort: String? = serverHost): DeviceMode =
        DeviceModeRouter.resolve(recommendedMode, modeFor(hostPort))

    fun saveMode(mode: DeviceMode, hostPort: String? = serverHost) {
        writeScopedString(KEY_MODE_PREFIX, serverForHost(hostPort), mode.name)
    }

    fun getModeMigrationVersion(): Int = prefs.getInt("mode_migration_version", 0)

    fun setModeMigrationVersion(version: Int) {
        prefs.edit { putInt("mode_migration_version", version) }
    }

    private fun tokenForServer(
        prefix: String,
        label: String,
        server: SavedServer?,
        migrateLegacy: Boolean = false,
    ): String {
        val key = server?.let { ServerSessionScope.preferenceKey(prefix, it) }
            ?: ServerSessionScope.hostPreferenceKey(prefix, null)
        prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val legacy = if (migrateLegacy && server?.instanceId == null && server?.hostPort == serverHost?.trim()) {
            prefs.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val token = legacy ?: label + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit(commit = true) {
            putString(key, token)
        }
        return token
    }

    private fun readScopedString(prefix: String, server: SavedServer?): String? {
        if (server == null) return null
        val key = ServerSessionScope.preferenceKey(prefix, server)
        return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun writeScopedString(prefix: String, server: SavedServer?, value: String) {
        if (server == null) return
        prefs.edit(commit = true) {
            putString(ServerSessionScope.preferenceKey(prefix, server), value)
        }
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
        writeSavedServersAndSelect(servers, null)
    }

    private fun writeSavedServersAndSelect(servers: List<SavedServer>, hostPort: String?) {
        val raw = json.encodeToString(ListSerializer(SavedServer.serializer()), servers)
        prefs.edit(commit = true) {
            putString(KEY_SAVED_SERVERS, raw)
            hostPort?.let { putString(KEY_HOST, it) }
        }
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_TOKEN = "client_token"
        private const val KEY_PLAYER_TOKEN_PREFIX = "player_token_"
        private const val KEY_USER_TOKEN_PREFIX = "user_token_"
        private const val KEY_NICKNAME_PREFIX = "nickname_"
        private const val KEY_MODE_PREFIX = "mode_"
        private const val KEY_MICROPHONE_MONITOR = "microphone_monitor_enabled"
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val KEY_PENDING_FINISHED_PREFIX = "pending_finished_queue_ids_"
        private const val KEY_PENDING_PLAYBACK_ERRORS_PREFIX = "pending_playback_errors_"
        private const val MAX_PENDING_PLAYBACK_ERRORS_JSON_CHARS = 1_000_000
        private const val MAX_SAVED_SERVERS = 10
        private const val MAX_NICKNAME_LENGTH = 32

        internal fun serverScopedPreferenceKey(prefix: String, serverHost: String?): String =
            ServerSessionScope.hostPreferenceKey(prefix, serverHost)

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
