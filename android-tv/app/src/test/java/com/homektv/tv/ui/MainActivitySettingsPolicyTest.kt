package com.homektv.tv.ui

import com.homektv.tv.session.DeviceSessionChangePolicy
import com.homektv.tv.session.DeviceSessionFingerprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySettingsPolicyTest {

    @Test
    fun serverHostChangeRequiresSessionRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080")
        val current = previous.copy(serverHost = "192.168.1.20:8080")
        assertTrue(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }

    @Test
    fun instanceIdChangeRequiresSessionRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080", instanceId = "id-a")
        val current = previous.copy(instanceId = "id-b")
        assertTrue(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }

    @Test
    fun unchangedSessionDoesNotRequireRestart() {
        val previous = DeviceSessionFingerprint("192.168.1.10:8080", instanceId = "id-a")
        val current = previous.copy()
        assertFalse(DeviceSessionChangePolicy.requiresRestart(previous, current))
    }
}
