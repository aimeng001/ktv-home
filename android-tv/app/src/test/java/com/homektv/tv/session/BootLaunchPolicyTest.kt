package com.homektv.tv.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLaunchPolicyTest {
    @Test
    fun controllerModeDoesNotLaunchAnActivityAtBoot() {
        assertFalse(BootLaunchPolicy.shouldLaunchPlayer(DeviceMode.CONTROLLER, configured = true))
    }

    @Test
    fun configuredPlayerAndCombinedModesLaunch() {
        assertTrue(BootLaunchPolicy.shouldLaunchPlayer(DeviceMode.PLAYER, configured = true))
        assertTrue(BootLaunchPolicy.shouldLaunchPlayer(DeviceMode.COMBINED, configured = true))
        assertFalse(BootLaunchPolicy.shouldLaunchPlayer(DeviceMode.PLAYER, configured = false))
    }
}
