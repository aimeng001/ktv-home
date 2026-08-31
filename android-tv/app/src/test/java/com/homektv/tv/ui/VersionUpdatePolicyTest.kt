package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUpdatePolicyTest {
    @Test
    fun prompts_only_when_server_version_is_newer() {
        assertFalse(isServerUpdateAvailable(currentVersionCode = 20, serverVersionCode = 19))
        assertFalse(isServerUpdateAvailable(currentVersionCode = 20, serverVersionCode = 20))
        assertTrue(isServerUpdateAvailable(currentVersionCode = 20, serverVersionCode = 21))
    }
}
