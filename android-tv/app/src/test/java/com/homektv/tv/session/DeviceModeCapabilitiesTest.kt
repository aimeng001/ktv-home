package com.homektv.tv.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModeCapabilitiesTest {

    @Test
    fun playerMode_doesNotExposeKiosk() {
        assertFalse(DeviceModeCapabilities.forMode(DeviceMode.PLAYER).canOpenKiosk)
    }

    @Test
    fun controllerMode_doesNotExposePlayerOrKiosk() {
        val capabilities = DeviceModeCapabilities.forMode(DeviceMode.CONTROLLER)

        assertFalse(capabilities.canPlayMedia)
        assertFalse(capabilities.canOpenKiosk)
    }

    @Test
    fun combinedMode_exposesBothPlayerAndKiosk() {
        val capabilities = DeviceModeCapabilities.forMode(DeviceMode.COMBINED)

        assertTrue(capabilities.canPlayMedia)
        assertTrue(capabilities.canOpenKiosk)
    }

    @Test
    fun controllerMode_mustEnterControllerActivity() {
        assertTrue(
            DeviceModeEntryPointPolicy.resolve(DeviceMode.CONTROLLER) ==
                DeviceModeEntryPoint.CONTROLLER_ACTIVITY,
        )
        assertTrue(
            DeviceModeEntryPointPolicy.resolve(DeviceMode.PLAYER) ==
                DeviceModeEntryPoint.PLAYER_ACTIVITY,
        )
    }
}
