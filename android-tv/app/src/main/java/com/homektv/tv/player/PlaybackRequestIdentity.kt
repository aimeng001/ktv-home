package com.homektv.tv.player

import androidx.media3.common.Player

internal data class PlaybackRequestIdentity(
    val queueId: Long?,
    val fileId: Long,
)

internal fun shouldReusePlaybackRequest(
    current: PlaybackRequestIdentity?,
    requested: PlaybackRequestIdentity,
    playbackState: Int,
): Boolean = current == requested && playbackState != Player.STATE_IDLE
