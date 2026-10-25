package com.homektv.tv.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import com.homektv.tv.session.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigStateTest {
    @Test
    fun serverSelectionPersistsNormalizedAddressAndMakesConfigurationVisible() {
        val config = AppConfig(InMemoryContext())

        assertFalse(config.isConfigured)
        assertTrue(config.savedServers.isEmpty())

        config.rememberServer(SavedServer(" https://ktv.local/path ", "  客厅  "))

        assertTrue(config.isConfigured)
        assertEquals("https://ktv.local:443", config.serverHost)
        assertEquals(listOf(SavedServer("https://ktv.local:443", "客厅")), config.savedServers)
    }

    @Test
    fun legacyHostMigratesOnceAndMalformedSavedServerJsonFailsClosed() {
        val context = InMemoryContext()
        context.preferences().edit().putString("server_host", "legacy.local:8080").commit()

        val config = AppConfig(context)

        assertEquals("legacy.local:8080", config.serverHost)
        assertEquals(listOf(SavedServer("legacy.local:8080", "legacy.local:8080")), config.savedServers)

        val malformed = InMemoryContext()
        malformed.preferences().edit().putString("saved_servers", "not-json").commit()
        assertTrue(AppConfig(malformed).savedServers.isEmpty())

        val whitespaceHost = InMemoryContext()
        whitespaceHost.preferences().edit().putString("server_host", "  ").commit()
        assertTrue(AppConfig(whitespaceHost).savedServers.isEmpty())
    }

    @Test
    fun serverLookupNormalizesHostAndUsesAnUnsavedFallback() {
        val config = AppConfig(InMemoryContext())

        assertNull(config.serverForHost(null))
        assertNull(config.serverForHost("  "))
        assertEquals(
            SavedServer("https://ktv.local:443", "https://ktv.local:443"),
            config.serverForHost("https://ktv.local/path"),
        )
    }

    @Test
    fun rememberServerPreservesExistingNameForHostLabelAndCapsHistoryAtTen() {
        val config = AppConfig(InMemoryContext())
        config.rememberServer(SavedServer("nas-a", "客厅"))
        config.rememberServer(SavedServer("http://nas-a/path", "nas-a:8080"))

        assertEquals("客厅", config.savedServers.first().name)
        assertEquals("nas-a:8080", config.savedServers.first().hostPort)

        (1..11).forEach { index ->
            config.rememberServer(SavedServer("nas-$index", "NAS $index"))
        }

        assertEquals(10, config.savedServers.size)
        assertEquals("nas-11:8080", config.savedServers.first().hostPort)
        assertFalse(config.savedServers.any { it.hostPort == "nas-a:8080" })
    }

    @Test
    fun serverRemovalAndForgetClearScopedStateAndRepairSelection() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val first = SavedServer("nas-a:8080", "A")
        val second = SavedServer("nas-b:8080", "B")
        config.rememberServer(first)
        config.rememberServer(second)
        config.saveNickname("  房主  ", second.hostPort)
        config.savePendingFinishedQueueIds(listOf(7L), second.hostPort)
        val secondUserKey = ServerSessionScope.preferenceKey("user_token_", second)
        context.preferences().edit().putString(secondUserKey, "user-b").commit()

        config.forgetServer(second)

        assertEquals(listOf(first), config.savedServers)
        assertEquals(first.hostPort, config.serverHost)
        assertEquals("", config.nicknameFor(second.hostPort))
        assertTrue(config.pendingFinishedQueueIds(second.hostPort).isEmpty())
        assertFalse(context.preferences().contains(secondUserKey))

        config.removeSavedServer(first.hostPort)
        assertTrue(config.savedServers.isEmpty())
        config.forgetServer(first)
        assertNull(config.serverHost)
    }

    @Test
    fun pendingFinishedQueueIdsAreParsedSanitizedAndRemovedWhenEmpty() {
        val config = AppConfig(InMemoryContext())
        val server = SavedServer("nas-a:8080", "A")
        config.savePendingFinishedQueueIds(listOf(3L, -1L, 3L, 0L, 8L), server)

        assertEquals(listOf(3L, 8L), config.pendingFinishedQueueIds(server))
        config.savePendingFinishedQueueIds(emptyList(), server)
        assertTrue(config.pendingFinishedQueueIds(server).isEmpty())
        assertTrue(config.pendingFinishedQueueIds(serverHost = null).isEmpty())
    }

    @Test
    fun pendingPlaybackErrorsRejectCorruptAndOversizedDataAndSanitizeSavedValues() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val server = SavedServer("nas-a:8080", "A")
        val key = ServerSessionScope.preferenceKey("pending_playback_errors_", server)

        context.preferences().edit().putString(key, "{").commit()
        assertTrue(config.pendingPlaybackErrors(server).isEmpty())
        context.preferences().edit().putString(key, "x".repeat(1_000_001)).commit()
        assertTrue(config.pendingPlaybackErrors(server).isEmpty())

        config.savePendingPlaybackErrors(
            listOf(
                PendingPlaybackError(queueId = 0L),
                PendingPlaybackError(queueId = 2L, fileId = -4L, message = "  "),
                PendingPlaybackError(queueId = 2L, fileId = 9L, message = "duplicate"),
                PendingPlaybackError(queueId = 3L, fileId = 9L, message = "x".repeat(300)),
            ),
            server,
        )

        val restored = config.pendingPlaybackErrors(server)
        assertEquals(listOf(2L, 3L), restored.map { it.queueId })
        assertNull(restored.first().fileId)
        assertEquals("媒体播放失败", restored.first().message)
        assertEquals(256, restored.last().message.length)
        config.savePendingPlaybackErrors(emptyList(), server)
        assertTrue(config.pendingPlaybackErrors(server).isEmpty())
        assertTrue(config.pendingPlaybackErrors(null).isEmpty())
    }

    @Test
    fun legacyScopeMigrationCopiesServerStateAndPreservesTargetValues() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val source = SavedServer("nas-a:8080", "A")
        val target = source.copy(instanceId = "00000000-0000-0000-0000-000000000001")
        val sourceUserKey = ServerSessionScope.preferenceKey("user_token_", source)
        val targetUserKey = ServerSessionScope.preferenceKey("user_token_", target)
        val sourceNicknameKey = ServerSessionScope.preferenceKey("nickname_", source)
        val sourceModeKey = ServerSessionScope.preferenceKey("mode_", source)

        context.preferences().edit()
            .putString(sourceUserKey, " old-user ")
            .putString(targetUserKey, "target-user")
            .putString(sourceNicknameKey, "房主")
            .putString(sourceModeKey, "CONTROLLER")
            .putString("pending_finished_queue_ids_nas-a:8080", "5,7")
            .putString("pending_playback_errors_nas-a:8080", "[{\"queueId\":5,\"fileId\":6,\"message\":\"failed\"}]")
            .putString("client_token", "legacy-token")
            .commit()

        assertTrue(config.migrateLegacyScope(source, target))

        assertEquals("target-user", context.preferences().getString(targetUserKey, null))
        assertEquals("房主", context.preferences().getString(
            ServerSessionScope.preferenceKey("nickname_", target), null,
        ))
        assertEquals("CONTROLLER", context.preferences().getString(
            ServerSessionScope.preferenceKey("mode_", target), null,
        ))
        assertEquals(listOf(5L, 7L), config.pendingFinishedQueueIds(target))
        assertEquals(listOf(5L), config.pendingPlaybackErrors(target).map { it.queueId })
        assertFalse(context.preferences().contains(sourceUserKey))
        assertFalse(context.preferences().contains("client_token"))
    }

    @Test
    fun legacyScopeMigrationRejectsInvalidPairsAndKeepsPlainCredentialOnEncryptionFailure() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val source = SavedServer("nas-a:8080", "A")
        val target = source.copy(instanceId = "00000000-0000-0000-0000-000000000001")
        assertFalse(config.migrateLegacyScope(source.copy(hostPort = "nas-b:8080"), target))
        assertFalse(config.migrateLegacyScope(source.copy(instanceId = "00000000-0000-0000-0000-000000000002"), target))
        assertFalse(config.migrateLegacyScope(source, source))

        context.preferences().edit()
            .putString("server_host", source.hostPort)
            .putString("player_credential", "secret")
            .putString(ServerSessionScope.preferenceKey("user_token_", source), "old-user")
            .commit()
        val withLegacyCredential = AppConfig(
            context,
            CredentialKeyProvider { throw IllegalStateException("synthetic keystore failure") },
        )
        val result = withLegacyCredential.migrateLegacyScope(source, target)

        assertFalse(result)
        assertEquals("secret", context.preferences().getString("player_credential", null))
        assertEquals(
            "old-user",
            context.preferences().getString(ServerSessionScope.preferenceKey("user_token_", source), null),
        )
    }

    @Test
    fun forgettingOneHostDoesNotClearLegacyCredentialOwnedByAnotherHost() {
        val context = InMemoryContext()
        val store = CredentialStore(context)
        val owner = SavedServer("nas-owner:8080", "Owner")
        val unrelated = SavedServer("nas-unrelated:8080", "Unrelated")
        context.preferences().edit()
            .putString("server_host", owner.hostPort)
            .putString("player_credential", "owner-secret")
            .commit()

        store.clearAllScopes(unrelated)
        assertEquals("owner-secret", context.preferences().getString("player_credential", null))

        store.clearAllScopes(owner)
        assertNull(context.preferences().getString("player_credential", null))
    }

    @Test
    fun scopedTokensNicknameModeAndStartupPreferenceRemainStable() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val server = SavedServer("nas-a:8080", "A")
        config.rememberServer(server)
        context.preferences().edit().putString("client_token", " legacy-player ").commit()

        assertEquals("legacy-player", config.playerToken)
        assertEquals(config.playerToken, config.clientToken)
        assertNotEquals(config.playerToken, config.userToken)
        assertEquals(config.userToken, config.userToken)

        config.saveNickname("  演唱者  ", server.hostPort)
        assertEquals("演唱者", config.nicknameFor(server.hostPort))
        config.saveNickname("x".repeat(40), server.hostPort)
        assertEquals(32, config.nicknameFor(server.hostPort).length)
        config.saveNickname("", null)

        config.saveMode(DeviceMode.CONTROLLER, server.hostPort)
        assertEquals(DeviceMode.CONTROLLER, config.modeFor(server.hostPort))
        context.preferences().edit()
            .putString(ServerSessionScope.preferenceKey("mode_", server), "INVALID")
            .commit()
        assertNull(config.modeFor(server.hostPort))

        assertFalse(config.microphoneMonitorEnabled)
        config.microphoneMonitorEnabled = true
        assertTrue(config.microphoneMonitorEnabled)
        config.microphoneMonitorEnabled = false
        assertFalse(config.microphoneMonitorEnabled)
        config.setModeMigrationVersion(4)
        assertEquals(4, config.getModeMigrationVersion())
    }

    @Test
    fun websocketAndHttpUrlsUseTheSelectedServerAndKeepCredentialsOutOfTheUrl() {
        val config = AppConfig(InMemoryContext())
        config.rememberServer(SavedServer("https://ktv.local:8443", "TV"))

        assertTrue(config.wsUrl("token with spaces").startsWith("wss://ktv.local:8443/ws?"))
        assertTrue(config.wsUrl("token with spaces").contains("client_token=token%20with%20spaces"))
        assertFalse(config.wsUrl("secret").contains("player_credential"))
        assertEquals("https://ktv.local:8443/api", config.apiBase())
        assertEquals("https://ktv.local:8443/m", config.h5Url())

        val legacyHttp = AppConfig(InMemoryContext())
        legacyHttp.rememberServer(SavedServer("nas.local:8080", "NAS"))
        assertTrue(legacyHttp.wsUrl("token").startsWith("ws://nas.local:8080/ws?"))
        assertEquals("http://nas.local:8080/api", legacyHttp.apiBase())
    }

    @Test
    fun blankAndInvalidStateInputsDoNotCreateOrLeakScopedPreferences() {
        val context = InMemoryContext()
        val config = AppConfig(context)

        config.serverHost = "  "
        assertFalse(config.isConfigured)
        config.serverHost = null
        assertNull(config.serverForHost("/"))
        config.rememberServer(SavedServer("/", "invalid"))
        assertTrue(config.savedServers.isEmpty())

        val server = SavedServer("nas-a:8080", "A")
        config.rememberServer(SavedServer(server.hostPort, "  "))
        assertEquals(server.hostPort, config.savedServers.single().name)
        config.rememberServer(SavedServer(server.hostPort, " "))
        assertEquals(server.hostPort, config.savedServers.single().name)

        assertTrue(config.pendingFinishedQueueIds(server).isEmpty())
        assertTrue(config.pendingPlaybackErrors(server).isEmpty())
        config.serverHost = null
        assertEquals("http:///api", config.apiBase())
        assertEquals("http:///m", config.h5Url())
        assertEquals(buildTvWebSocketUrl("", "token"), config.wsUrlFor(null, "token"))
        assertEquals("", config.nicknameFor(null))
        assertNull(config.modeFor(null))
        assertNull(config.modeFor(server.hostPort))
        config.saveNickname("  ", server.hostPort)
        assertEquals("", config.nicknameFor(server.hostPort))
        config.saveNickname("unused", null)
        config.saveMode(DeviceMode.PLAYER, null)
        config.savePendingFinishedQueueIds(emptyList(), server = null)
        config.savePendingPlaybackErrors(emptyList(), null)
        assertTrue(config.pendingFinishedQueueIds(server).isEmpty())

        context.preferences().edit().remove("saved_servers").commit()
        assertTrue(config.savedServers.isEmpty())

        context.preferences().edit()
            .putString("pending_finished_queue_ids_nas-a:8080", "not-a-number,-1,3, ,9223372036854775808")
            .commit()
        assertEquals(listOf(3L), config.pendingFinishedQueueIds(server))
    }

    @Test
    fun forgettingUnselectedServerKeepsSelectionAndForgettingLastServerClearsIt() {
        val config = AppConfig(InMemoryContext())
        val first = SavedServer("nas-a:8080", "A")
        val second = SavedServer("nas-b:8080", "B")
        config.rememberServer(first)
        config.rememberServer(second)

        config.forgetServer(first)
        assertEquals(second.hostPort, config.serverHost)
        assertEquals(listOf(second), config.savedServers)

        config.forgetServer(SavedServer("/", "invalid"))
        assertEquals(listOf(second), config.savedServers)
        config.forgetServer(second)
        assertTrue(config.savedServers.isEmpty())
        assertNull(config.serverHost)

        val unselected = AppConfig(InMemoryContext())
        unselected.forgetServer(SavedServer("nas-c:8080", "C"))
        assertNull(unselected.serverHost)
    }

    @Test
    fun tokenMigrationUsesOnlyTheMatchingLegacyHostAndCreatesMissingScopedTokens() {
        val context = InMemoryContext()
        val config = AppConfig(context)
        val server = SavedServer("nas-a:8080", "A")
        config.rememberServer(server)
        assertNull(config.modeFor(server.hostPort))
        assertEquals("", config.nicknameFor(server.hostPort))
        context.preferences().edit().putString("client_token", "   ").commit()

        val generatedPlayerToken = config.playerToken
        assertTrue(generatedPlayerToken.startsWith("tv-"))
        assertEquals(generatedPlayerToken, config.playerToken)

        context.preferences().edit().putString("client_token", "legacy-global").commit()
        val identifiedServer = server.copy(instanceId = "00000000-0000-0000-0000-000000000002")
        val identifiedToken = config.playerTokenFor(identifiedServer)
        assertTrue(identifiedToken.startsWith("tv-"))
        assertNotEquals("legacy-global", identifiedToken)

        val otherPlayerToken = config.playerTokenFor(SavedServer("nas-other:8080", "Other"))
        assertTrue(otherPlayerToken.startsWith("tv-"))
        assertNotEquals("legacy-global", otherPlayerToken)
        assertEquals("legacy-global", context.preferences().getString("client_token", null))

        val blankScope = SavedServer("nas-blank:8080", "Blank")
        context.preferences().edit()
            .putString(ServerSessionScope.preferenceKey("user_token_", blankScope), "  ")
            .commit()
        assertTrue(config.userTokenFor(blankScope).startsWith("user-"))
        val hostlessToken = config.userTokenFor(null as String?)
        assertTrue(hostlessToken.startsWith("user-"))
        assertEquals(hostlessToken, config.userTokenFor(null as String?))
        val hostlessPlayerToken = config.playerTokenFor(null)
        assertTrue(hostlessPlayerToken.startsWith("tv-"))
        assertEquals(hostlessPlayerToken, config.playerTokenFor(null))

        val noLegacyContext = InMemoryContext()
        val noLegacy = AppConfig(noLegacyContext)
        noLegacy.rememberServer(SavedServer("nas-no-legacy:8080", "No legacy"))
        assertTrue(noLegacy.playerToken.startsWith("tv-"))

        val hostlessLegacyContext = InMemoryContext()
        hostlessLegacyContext.preferences().edit().putString("client_token", "legacy-hostless").commit()
        val hostlessLegacy = AppConfig(hostlessLegacyContext)
        assertEquals("legacy-hostless", hostlessLegacy.playerTokenFor(null))
    }

    @Test
    fun controllerWebSocketUsesInjectedPlatformAndEffectiveModeForSavedAndMissingServer() {
        val phone = AppConfigPlatformInfo(
            hasLeanback = false,
            hasTouchscreen = true,
            uiMode = Configuration.UI_MODE_TYPE_NORMAL,
            smallestScreenWidthDp = 400,
        )
        val config = AppConfig(InMemoryContext(), phone)
        val server = SavedServer("nas-a:8080", "A")
        config.rememberServer(server)
        config.saveMode(DeviceMode.PLAYER, server.hostPort)

        assertEquals(
            "ws://nas-a:8080/ws?client_type=controller&client_token=user%20token&protocol_version=2&platform=ANDROID_PHONE&device_mode=PLAYER",
            config.controllerWsUrlFor(server, "user token"),
        )
        assertTrue(config.controllerWsUrlFor(null, "").contains("device_mode=CONTROLLER"))
    }

    @Test
    fun platformAndModeRecommendationsCoverLeanbackUiModeAndScreenWidthPaths() {
        val leanback = AppConfig(
            InMemoryContext(),
            AppConfigPlatformInfo(true, true, Configuration.UI_MODE_TYPE_NORMAL, 400),
        )
        assertEquals("ANDROID_TV", leanback.controllerPlatform())
        assertEquals(DeviceMode.COMBINED, leanback.recommendedMode)

        val televisionUiMode = AppConfig(
            InMemoryContext(),
            AppConfigPlatformInfo(false, false, Configuration.UI_MODE_TYPE_TELEVISION, 400),
        )
        assertEquals("ANDROID_TV", televisionUiMode.controllerPlatform())
        assertEquals(DeviceMode.COMBINED, televisionUiMode.recommendedMode)

        val tablet = AppConfig(
            InMemoryContext(),
            AppConfigPlatformInfo(false, true, Configuration.UI_MODE_TYPE_NORMAL, 600),
        )
        assertEquals("ANDROID_TABLET", tablet.controllerPlatform())
        assertEquals(DeviceMode.CONTROLLER, tablet.recommendedMode)

        val phone = AppConfig(
            InMemoryContext(),
            AppConfigPlatformInfo(false, true, Configuration.UI_MODE_TYPE_NORMAL, 599),
        )
        assertEquals("ANDROID_PHONE", phone.controllerPlatform())
        assertEquals(DeviceMode.CONTROLLER, phone.recommendedMode)
    }

    @Test
    fun legacyScopeMigrationCoversCopyPreserveAndBlankSourceCombinations() {
        val prefixes = listOf("user_token_", "player_token_", "nickname_", "mode_")
        val scenarios: List<List<Pair<String?, String?>>> = listOf(
            listOf("source-user" to null, "source-player" to null, "source-name" to null, "source-mode" to null),
            listOf("source-user" to "  ", "source-player" to "target-player", "source-name" to "target-name", "  " to "target-mode"),
            listOf(null to null, null to null, null to null, null to "target-mode"),
            listOf("  " to null, "  " to null, "  " to null, "  " to null),
            listOf("source-user" to "  ", "source-player" to "  ", "source-name" to "  ", "source-mode" to "  "),
            listOf(null to null, null to null, null to null, "source-mode" to "target-mode"),
        )

        scenarios.forEachIndexed { index, values ->
            val context = InMemoryContext()
            val config = AppConfig(context)
            val source = SavedServer("nas-$index:8080", "NAS $index")
            val target = source.copy(instanceId = "00000000-0000-0000-0000-${(index + 10).toString().padStart(12, '0')}")
            values.forEachIndexed { valueIndex, (fromValue, toValue) ->
                val prefix = prefixes[valueIndex]
                val fromKey = ServerSessionScope.preferenceKey(prefix, source)
                val toKey = ServerSessionScope.preferenceKey(prefix, target)
                context.preferences().edit().apply {
                    if (fromValue != null) putString(fromKey, fromValue)
                    if (toValue != null) putString(toKey, toValue)
                }.commit()
            }

            assertTrue("scenario $index", config.migrateLegacyScope(source, target))
            values.forEachIndexed { valueIndex, (fromValue, toValue) ->
                val prefix = prefixes[valueIndex]
                val toKey = ServerSessionScope.preferenceKey(prefix, target)
                val expected = toValue?.takeIf { it.isNotBlank() } ?: fromValue?.takeIf { it.isNotBlank() }
                assertEquals("scenario $index prefix $prefix", expected, context.preferences().getString(toKey, null))
            }
        }
    }
}

private class InMemoryContext : ContextWrapper(null) {
    private val preferences = mutableMapOf<String, InMemoryPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name) { InMemoryPreferences() }

    fun preferences(name: String = "ktv_tv"): InMemoryPreferences =
        preferences.getOrPut(name) { InMemoryPreferences() }
}

private class InMemoryPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearBeforeApply = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toMutableSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = apply {
            updates.remove(key)
            removals += key
        }
        override fun clear(): SharedPreferences.Editor = apply { clearBeforeApply = true }
        override fun commit(): Boolean {
            if (clearBeforeApply) values.clear()
            removals.forEach(values::remove)
            updates.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }
        override fun apply() { commit() }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            removals.remove(key)
            updates[key] = value
        }
    }
}
