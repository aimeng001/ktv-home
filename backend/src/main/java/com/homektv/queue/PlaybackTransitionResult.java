package com.homektv.queue;

import com.homektv.domain.PlayerState;

/** Result of a playback transition, including stale-client rejection. */
public record PlaybackTransitionResult(boolean accepted, PlayerState state) {

    public static PlaybackTransitionResult accepted(PlayerState state) {
        return new PlaybackTransitionResult(true, state);
    }

    public static PlaybackTransitionResult rejected(PlayerState state) {
        return new PlaybackTransitionResult(false, state);
    }
}
