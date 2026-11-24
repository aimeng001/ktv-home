package com.homektv.queue;

import com.homektv.domain.PlayerState;

/** Result of a playback transition, including stale-client rejection and circuit breaking. */
public record PlaybackTransitionResult(boolean accepted, PlayerState state, boolean circuitBroken) {

    public PlaybackTransitionResult(boolean accepted, PlayerState state) {
        this(accepted, state, false);
    }

    public static PlaybackTransitionResult accepted(PlayerState state) {
        return new PlaybackTransitionResult(true, state, false);
    }

    public static PlaybackTransitionResult circuitBroken(PlayerState state) {
        return new PlaybackTransitionResult(true, state, true);
    }

    public static PlaybackTransitionResult rejected(PlayerState state) {
        return new PlaybackTransitionResult(false, state, false);
    }
}
