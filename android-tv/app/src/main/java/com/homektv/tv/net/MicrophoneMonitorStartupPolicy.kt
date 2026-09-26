package com.homektv.tv.net

/** Microphone monitoring is optional and must not request privacy permission on app startup. */
object MicrophoneMonitorStartupPolicy {
    const val DEFAULT_ENABLED = false

    fun shouldRestoreMonitoring(savedEnabled: Boolean, recordAudioPermissionGranted: Boolean): Boolean =
        savedEnabled && recordAudioPermissionGranted
}
