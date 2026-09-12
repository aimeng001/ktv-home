package com.homektv.tv.session

/**
 * Identities used by one server-bound Android session.
 *
 * The player token owns the TV playback lease. The user token owns ordering,
 * favourites, history and room permissions. Keeping both values in one small
 * immutable value makes it harder for a controller request to accidentally
 * reuse the player identity.
 */
data class SessionIdentity(
    val serverHost: String,
    val playerToken: String,
    val userToken: String,
    val nickname: String = "",
) {
    val serverKey: String
        get() = serverHost.trim()
}
