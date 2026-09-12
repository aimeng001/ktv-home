package com.homektv.tv.ui

import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceSessionChangePolicy
import com.homektv.tv.session.DeviceSessionFingerprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySettingsPolicyTest {

    @Test
    fun serverHostChangeRequiresSessionRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080", DeviceMode.COMBINED, "TV")
        val current = previous.copy(serverHost = "192.168.1.20:8080")
        assertTrue(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }

    @Test
    fun modeChangeRequiresSessionRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080", DeviceMode.COMBINED, "TV")
        val current = previous.copy(mode = DeviceMode.CONTROLLER)
        assertTrue(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }

    @Test
    fun unchangedSessionDoesNotRequireRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080", DeviceMode.COMBINED, "TV")
        val current = previous.copy()
        assertFalse(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }
}
