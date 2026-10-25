package com.homektv.tv.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun interface CredentialKeyProvider {
    fun getOrCreate(): SecretKey
}

/**
 * Stores the optional TV WebSocket credential encrypted by Android Keystore.
 * A legacy plaintext value is migrated only after encryption succeeds.
 */
internal class CredentialStore(
    context: Context,
    private val keyProvider: CredentialKeyProvider? = null,
) {
    private val securePrefs = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun read(server: SavedServer?): String {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        val stored = securePrefs.getString(scopedKey, null)
        if (!stored.isNullOrBlank()) return decrypt(stored).orEmpty()

        // Once an instance id is known, do not fall back to another instance's
        // host namespace. A newly identified server starts with an empty scope.
        if (server?.instanceId != null) return ""

        val legacyHost = legacyPrefs.getString(LEGACY_HOST_KEY, null)
        val legacy = if (shouldMigrateLegacyCredential(legacyHost, scope)) {
            legacyPrefs.getString(LEGACY_KEY, null)?.trim().orEmpty()
        } else {
            ""
        }
        if (legacy.isBlank() || !writeEncrypted(scopedKey, legacy)) return ""
        legacyPrefs.edit().remove(LEGACY_KEY).apply()
        return legacy
    }

    @Synchronized
    fun write(value: String, server: SavedServer?) {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            securePrefs.edit().remove(scopedKey).apply()
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
            return
        }
        // Fail closed: never introduce a new plaintext fallback if Keystore is
        // unavailable or the key has been invalidated.
        if (writeEncrypted(scopedKey, trimmed) &&
            shouldMigrateLegacyCredential(legacyPrefs.getString(LEGACY_HOST_KEY, null), scope)) {
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    @Synchronized
    fun clear(server: SavedServer?) {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        securePrefs.edit().remove(scopedKey).apply()
        clearLegacyCredentialFor(scope)
    }

    /**
     * Forgetting a server must remove both the identity namespace and the
     * legacy host namespace. Otherwise selecting the same host again can
     * resurrect a credential that belonged to the forgotten instance.
     */
    @Synchronized
    fun clearAllScopes(server: SavedServer?) {
        val hostKey = scopedKey(server?.hostPort.orEmpty().trim())
        val identityKey = server?.instanceId?.let { identityKey(it) }
        val editor = securePrefs.edit()
        editor.remove(hostKey)
        identityKey?.let(editor::remove)
        editor.apply()
        clearLegacyCredentialFor(server?.hostPort.orEmpty().trim())
    }

    @Synchronized
    fun migrate(from: SavedServer?, to: SavedServer): Boolean {
        if (from == null) return true
        val sourceHost = from.hostPort.trim()
        val oldCred = read(from)
        if (oldCred.isBlank()) {
            // A matching legacy secret can remain when encryption failed. Do
            // not treat that failure as an empty source and let the caller
            // clear the only copy of the user's credential.
            val matchingLegacyRemains = from.instanceId == null &&
                shouldMigrateLegacyCredential(legacyPrefs.getString(LEGACY_HOST_KEY, null), sourceHost) &&
                !legacyPrefs.getString(LEGACY_KEY, null).isNullOrBlank()
            return !matchingLegacyRemains
        }
        if (read(to).isBlank()) {
            write(oldCred, to)
        }
        return read(to) == oldCred
    }

    private fun clearLegacyCredentialFor(serverHost: String) {
        if (shouldMigrateLegacyCredential(legacyPrefs.getString(LEGACY_HOST_KEY, null), serverHost)) {
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    private fun writeEncrypted(scopedKey: String, value: String): Boolean {
        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val payload = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            encode(cipher.iv) + SEPARATOR + encode(payload)
        }.getOrNull() ?: return false
        return securePrefs.edit().putString(scopedKey, encoded).commit()
    }

    private fun decrypt(stored: String): String? = runCatching {
        val pieces = stored.split(SEPARATOR, limit = 2)
        require(pieces.size == 2)
        val iv = decode(pieces[0])
        val payload = decode(pieces[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        keyProvider?.let { return it.getOrCreate() }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(value: ByteArray): String =
        Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    private fun scopedKey(serverHost: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(serverHost.toByteArray(StandardCharsets.UTF_8))
        val suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return KEY_CIPHERTEXT_PREFIX + suffix
    }

    private fun identityKey(instanceId: String): String =
        scopedKey("instance:${instanceId.trim()}")

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "home-ktv-player-credential-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SECURE_PREFS = "ktv_tv_secure"
        private const val LEGACY_PREFS = "ktv_tv"
        private const val LEGACY_KEY = "player_credential"
        private const val LEGACY_HOST_KEY = "server_host"
        private const val KEY_CIPHERTEXT_PREFIX = "player_credential_gcm_"
        private const val SEPARATOR = "."
    }
}

/** Legacy credentials may only migrate into the namespace of their old server. */
internal fun shouldMigrateLegacyCredential(legacyHost: String?, requestedHost: String?): Boolean {
    val normalizedLegacy = legacyHost?.let(AppConfig::normalizeHost) ?: return false
    return normalizedLegacy == requestedHost?.trim().orEmpty()
}
