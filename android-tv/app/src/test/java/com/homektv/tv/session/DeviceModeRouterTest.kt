package com.homektv.tv.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModeRouterTest {
    @Test
    fun tvDefaultsToCombinedMode() {
        assertEquals(
            DeviceMode.COMBINED,
            DeviceModeRouter.recommend(DeviceCapabilities(isTelevision = true, hasTouchscreen = false)),
        )
    }

    @Test
    fun touchDevicesDefaultToControllerMode() {
        assertEquals(
            DeviceMode.CONTROLLER,
            DeviceModeRouter.recommend(DeviceCapabilities(isTelevision = false, hasTouchscreen = true)),
        )
    }

    @Test
    fun nonTouchBoxWithoutLeanbackFeatureGetsCombinedMode() {
        assertEquals(
            DeviceMode.COMBINED,
            DeviceModeRouter.recommend(DeviceCapabilities(isTelevision = false, hasTouchscreen = false)),
        )
    }

    @Test
    fun manualModeAlwaysWinsOverRecommendation() {
        assertEquals(
            DeviceMode.PLAYER,
            DeviceModeRouter.resolve(
                recommended = DeviceMode.CONTROLLER,
                saved = DeviceMode.PLAYER,
            ),
        )
    }
}
