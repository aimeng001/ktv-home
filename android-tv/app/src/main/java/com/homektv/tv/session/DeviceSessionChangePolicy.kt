package com.homektv.tv.session

/** Settings that change the server-side identity or activity role. */
data class DeviceSessionFingerprint(
    val serverHost: String?,
    val mode: DeviceMode,
    val nickname: String,
    val instanceId: String? = null,
)

object DeviceSessionChangePolicy {
    fun requiresRestart(
        previous: DeviceSessionFingerprint,
        current: DeviceSessionFingerprint,
    ): Boolean = previous != current
}
