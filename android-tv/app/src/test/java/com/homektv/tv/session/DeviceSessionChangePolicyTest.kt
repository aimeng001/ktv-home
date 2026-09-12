package com.homektv.tv.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionChangePolicyTest {

    @Test
    fun unchangedServerModeAndNickname_keepCurrentSession() {
        val identity = DeviceSessionFingerprint("nas:8080", DeviceMode.COMBINED, "客厅")

        assertFalse(DeviceSessionChangePolicy.requiresRestart(identity, identity.copy()))
    }

    @Test
    fun nicknameChange_requiresSessionRestart() {
        val old = DeviceSessionFingerprint("nas:8080", DeviceMode.COMBINED, "客厅")
        val current = old.copy(nickname = "卧室")

        assertTrue(DeviceSessionChangePolicy.requiresRestart(old, current))
    }
}
