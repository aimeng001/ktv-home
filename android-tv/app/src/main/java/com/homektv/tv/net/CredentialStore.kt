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
 */
internal class CredentialStore(
    context: Context,
    private val keyProvider: CredentialKeyProvider? = null,
) {
    private val securePrefs = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
    private var cachedKey: SecretKey? = null
    private val cachedValues = HashMap<String, String>(4)

    @Synchronized
    fun read(server: SavedServer?): String {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        cachedValues[scopedKey]?.let { return it }
        val stored = securePrefs.getString(scopedKey, null) ?: return ""
        val decrypted = decrypt(stored).orEmpty()
        cachedValues[scopedKey] = decrypted
        return decrypted
    }

    @Synchronized
    fun write(value: String, server: SavedServer?) {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            cachedValues.remove(scopedKey)
            securePrefs.edit().remove(scopedKey).apply()
            return
        }
        // Fail closed: never introduce a new plaintext fallback if Keystore is
        // unavailable or the key has been invalidated.
        if (writeEncrypted(scopedKey, trimmed)) {
            cachedValues[scopedKey] = trimmed
        }
    }

    @Synchronized
    fun clear(server: SavedServer?) {
        val scope = server?.hostPort.orEmpty().trim()
        val scopedKey = server?.instanceId?.let { identityKey(it) } ?: scopedKey(scope)
        cachedValues.remove(scopedKey)
        securePrefs.edit().remove(scopedKey).apply()
    }

    /**
     * Forgetting a server must remove both the identity namespace and the
     * host namespace.
     */
    @Synchronized
    fun clearAllScopes(server: SavedServer?) {
        val hostKey = scopedKey(server?.hostPort.orEmpty().trim())
        val identityKey = server?.instanceId?.let { identityKey(it) }
        cachedValues.remove(hostKey)
        identityKey?.let(cachedValues::remove)
        val editor = securePrefs.edit()
        editor.remove(hostKey)
        identityKey?.let(editor::remove)
        editor.apply()
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
        cachedKey?.let { return it }
        keyProvider?.let {
            val resolved = it.getOrCreate()
            cachedKey = resolved
            return resolved
        }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val resolved = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
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
        cachedKey = resolved
        return resolved
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
        private const val KEY_CIPHERTEXT_PREFIX = "player_credential_gcm_"
        private const val SEPARATOR = "."
    }
}
