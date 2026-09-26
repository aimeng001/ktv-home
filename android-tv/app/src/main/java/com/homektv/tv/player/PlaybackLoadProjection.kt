package com.homektv.tv.player

import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.QueueSnapshot

/** Latest server state used when an asynchronous media load reaches its commit point. */
internal class DesiredPlaybackState {
    @Volatile
    private var latest: QueueSnapshot? = null

    fun update(snapshot: QueueSnapshot) {
        latest = snapshot
    }

    fun forQueue(queueId: Long?): QueueSnapshot? {
        val snapshot = latest ?: return null
        return snapshot.playing
            ?.takeIf { it.queueId == queueId }
            ?.let { snapshot }
    }
}

internal data class PlaybackLoadCommand(
    val fileId: Long,
    val streamUrl: String,
    val queueId: Long?,
    val vocalMode: String,
    val accompanimentTrackIndex: Int?,
    val audioTrackCount: Int,
    val audioLayout: AudioLayout,
    val volume: Int,
    val muted: Boolean,
    val state: String,
    val positionMs: Long,
    val seekSequence: Long,
) {
    val playWhenReady: Boolean
        get() = !state.equals("paused", ignoreCase = true)
}

/**
 * Builds the one playback command that should be committed after metadata has loaded.
 * Reading the state here, immediately before the player calls, prevents an older snapshot
 * captured before a slow detail/lyric/cover request from winning the race.
 */
internal class PlaybackLoadProjection(private val desiredState: DesiredPlaybackState) {
    fun commandForLoadedFile(
        queueId: Long?,
        fileId: Long,
        streamUrl: String,
        accompanimentTrackIndex: Int?,
        audioTrackCount: Int,
        audioLayout: AudioLayout,
        recovery: PlaybackReplacementRequest? = null,
    ): PlaybackLoadCommand? {
        val snapshot = desiredState.forQueue(queueId) ?: return null
        val recoveryApplies = recovery?.takeIf {
            it.forceTranscode && LiveTranscodeStreamUrl.isLiveTranscode(streamUrl)
        }
        val recoverySeekIsCurrent = recoveryApplies?.recoverySeekSequence == null ||
            recoveryApplies.recoverySeekSequence == snapshot.seekSequence
        val positionMs = if (recoveryApplies?.recoveryPositionMs != null && recoverySeekIsCurrent) {
            maxOf(snapshot.positionMs, recoveryApplies.recoveryPositionMs.coerceAtLeast(0L))
        } else snapshot.positionMs.coerceAtLeast(0L)
        val stateUnchanged = recoveryApplies?.stateAtFailure == null ||
            recoveryApplies.stateAtFailure.equals(snapshot.state, ignoreCase = true)
        val state = if (stateUnchanged && recoveryApplies?.recoveryPlayWhenReady != null) {
            if (recoveryApplies.recoveryPlayWhenReady) "playing" else "paused"
        } else snapshot.state
        val effectiveStreamUrl = if (LiveTranscodeStreamUrl.isLiveTranscode(streamUrl)) {
            LiveTranscodeStreamUrl.withStart(streamUrl, positionMs)
        } else streamUrl
        return PlaybackLoadCommand(
            fileId = fileId,
            streamUrl = effectiveStreamUrl,
            queueId = queueId,
            vocalMode = snapshot.vocalMode,
            accompanimentTrackIndex = accompanimentTrackIndex,
            audioTrackCount = audioTrackCount,
            audioLayout = audioLayout,
            volume = snapshot.volume,
            muted = snapshot.muted,
            state = state,
            positionMs = positionMs,
            seekSequence = snapshot.seekSequence,
        )
    }
}
