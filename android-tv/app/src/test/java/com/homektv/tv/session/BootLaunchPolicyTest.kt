package com.homektv.tv.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLaunchPolicyTest {
    @Test
    fun configuredTvInstallationLaunchesAtBootAndUnconfiguredStaysInSetup() {
        assertTrue(BootLaunchPolicy.shouldLaunchPlayer(configured = true))
        assertFalse(BootLaunchPolicy.shouldLaunchPlayer(configured = false))
    }
}
