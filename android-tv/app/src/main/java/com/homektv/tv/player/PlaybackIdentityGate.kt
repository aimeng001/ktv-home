package com.homektv.tv.player

/**
 * Keeps the identity used by player callbacks separate from the request that
 * is still being loaded. A replacement suppresses callbacks until its media
 * item is confirmed ready.
 */
internal data class PlaybackIdentity(
    val queueId: Long?,
    val fileId: Long,
    val mediaId: String,
)

internal class PlaybackIdentityGate {
    private var pending: PlaybackIdentity? = null
    private var active: PlaybackIdentity? = null
    private var switching = false

    fun begin(request: PlaybackIdentity) {
        pending = request
        switching = true
    }

    fun markReady(mediaId: String): Boolean {
        val request = pending ?: return false
        if (request.mediaId != mediaId) return false

        active = request
        pending = null
        switching = false
        return true
    }

    fun activeIdentity(): PlaybackIdentity? = active

    fun callbackIdentity(): PlaybackIdentity? =
        active?.takeUnless { switching }

    fun consumeFinishedIdentity(): PlaybackIdentity? {
        val finished = callbackIdentity() ?: return null
        active = null
        return finished
    }

    fun invalidate() {
        pending = null
        active = null
        switching = false
    }
}
