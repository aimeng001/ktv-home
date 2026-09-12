package com.homektv.tv.session

/** The local role of this installation. A TV may play and control the room. */
enum class DeviceMode {
    PLAYER,
    CONTROLLER,
    COMBINED,
}

enum class DeviceModeEntryPoint {
    PLAYER_ACTIVITY,
    CONTROLLER_ACTIVITY,
}

object DeviceModeEntryPointPolicy {
    fun resolve(mode: DeviceMode): DeviceModeEntryPoint =
        if (mode == DeviceMode.CONTROLLER) {
            DeviceModeEntryPoint.CONTROLLER_ACTIVITY
        } else {
            DeviceModeEntryPoint.PLAYER_ACTIVITY
        }
}

data class DeviceModeCapabilities(
    val canPlayMedia: Boolean,
    val canOpenKiosk: Boolean,
) {
    companion object {
        fun forMode(mode: DeviceMode): DeviceModeCapabilities = when (mode) {
            DeviceMode.PLAYER -> DeviceModeCapabilities(
                canPlayMedia = true,
                canOpenKiosk = false,
            )
            DeviceMode.CONTROLLER -> DeviceModeCapabilities(
                canPlayMedia = false,
                canOpenKiosk = false,
            )
            DeviceMode.COMBINED -> DeviceModeCapabilities(
                canPlayMedia = true,
                canOpenKiosk = true,
            )
        }
    }
}

/** The small set of capabilities needed for the first-run mode recommendation. */
data class DeviceCapabilities(
    val isTelevision: Boolean,
    val hasTouchscreen: Boolean,
)

object DeviceModeRouter {
    fun recommend(capabilities: DeviceCapabilities): DeviceMode =
        if (capabilities.isTelevision || !capabilities.hasTouchscreen) {
            DeviceMode.COMBINED
        } else {
            DeviceMode.CONTROLLER
        }

    fun resolve(recommended: DeviceMode, saved: DeviceMode?): DeviceMode = saved ?: recommended
}
