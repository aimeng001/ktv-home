package com.homektv.tv.net

/**
 * Builds the controller URL. Controller sessions have their own role and
 * platform/device-mode metadata; they never carry the TV player credential.
 */
internal fun buildControllerWebSocketUrl(
    serverHost: String,
    userToken: String,
    platform: String = "ANDROID_PHONE",
    deviceMode: String = "CONTROLLER",
): String {
    return "ws://$serverHost/ws?client_type=controller&client_token=" +
        "${encodeQueryComponent(userToken)}&protocol_version=2&platform=" +
        "${encodeQueryComponent(platform)}&device_mode=${encodeQueryComponent(deviceMode)}"
}
