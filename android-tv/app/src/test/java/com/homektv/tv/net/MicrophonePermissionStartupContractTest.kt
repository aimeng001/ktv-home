package com.homektv.tv.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionStartupContractTest {
    @Test
    fun freshInstallKeepsOptionalMicrophoneMonitorDisabled() {
        val source = locate("src/main/java/com/homektv/tv/net/AppConfig.kt").readText()

        assertTrue(source.contains("get() = prefs.getBoolean(KEY_MICROPHONE_MONITOR, MicrophoneMonitorStartupPolicy.DEFAULT_ENABLED)"))
        assertTrue(!source.contains("KEY_MICROPHONE_MONITOR, true)"))
    }

    @Test
    fun deniedPermissionClearsSavedAutomaticMicrophoneStart() {
        val source = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val callback = source.substringAfter("ActivityResultContracts.RequestMultiplePermissions()")
            .substringBefore("/** 当前请求中的 queueId")

        assertTrue(callback.contains("config.microphoneMonitorEnabled = false"))
    }

    @Test
    fun startupRestoresMicrophoneOnlyWhenPermissionIsAlreadyGranted() {
        val source = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()

        assertTrue(source.contains("MicrophoneMonitorStartupPolicy.shouldRestoreMonitoring"))
    }

    @Test
    fun startupPolicyRequiresExplicitEnablementAndGrantedRecordPermission() {
        assertTrue(!MicrophoneMonitorStartupPolicy.DEFAULT_ENABLED)
        assertTrue(!MicrophoneMonitorStartupPolicy.shouldRestoreMonitoring(savedEnabled = false, recordAudioPermissionGranted = true))
        assertTrue(!MicrophoneMonitorStartupPolicy.shouldRestoreMonitoring(savedEnabled = true, recordAudioPermissionGranted = false))
        assertTrue(MicrophoneMonitorStartupPolicy.shouldRestoreMonitoring(savedEnabled = true, recordAudioPermissionGranted = true))
    }

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app/$relativePath"),
            workingDirectory.resolve("../$relativePath"),
            workingDirectory.resolve("../../$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $relativePath")
    }
}
