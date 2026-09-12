package com.homektv.tv.session

object BootLaunchPolicy {
    fun shouldLaunchPlayer(mode: DeviceMode, configured: Boolean): Boolean =
        configured && mode != DeviceMode.CONTROLLER
}
