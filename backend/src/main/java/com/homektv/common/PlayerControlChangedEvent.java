package com.homektv.common;

/**
 * Published after lightweight player-control commits (volume, mute, vocal mode, or seek)
 * so cached queue snapshots can update player fields without re-querying the full queue.
 */
public record PlayerControlChangedEvent(
        Long currentQueueId,
        String state,
        int volume,
        boolean muted,
        String vocalMode,
        long positionMs,
        long seekSequence
) {
}
