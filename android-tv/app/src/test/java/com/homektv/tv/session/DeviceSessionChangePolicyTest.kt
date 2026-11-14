package com.homektv.tv.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionChangePolicyTest {

    @Test
    fun unchangedServerAndInstance_keepCurrentSession() {
        val identity = DeviceSessionFingerprint("nas:8080")

        assertFalse(DeviceSessionChangePolicy.requiresRestart(identity, identity.copy()))
    }

    @Test
    fun serverHostChange_requiresSessionRestart() {
        val old = DeviceSessionFingerprint("nas:8080")
        val current = old.copy(serverHost = "nas:8081")

        assertTrue(DeviceSessionChangePolicy.requiresRestart(old, current))
    }

    @Test
    fun serverInstanceChange_requiresSessionRestartEvenWhenHostIsUnchanged() {
        val old = DeviceSessionFingerprint(
            "nas:8080",
            instanceId = "550e8400-e29b-41d4-a716-446655440000",
        )
        val current = old.copy(instanceId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")

        assertTrue(DeviceSessionChangePolicy.requiresRestart(old, current))
    }
}
