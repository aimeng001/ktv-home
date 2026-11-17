package com.homektv.tv.session

/** Settings that change the server-side identity or player credential. */
data class DeviceSessionFingerprint(
    val serverHost: String?,
    val instanceId: String? = null,
    val credentialHash: String? = null,
)

object DeviceSessionChangePolicy {
    fun fingerprintCredential(credential: String?): String? {
        val value = credential?.trim().orEmpty()
        if (value.isEmpty()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun requiresRestart(
        previous: DeviceSessionFingerprint,
        current: DeviceSessionFingerprint,
    ): Boolean = previous != current
}
