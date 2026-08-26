package com.homektv.queue;

import com.homektv.domain.PlayerState;

/** Result of a client-reported position update, including stale-packet rejection. */
public record PositionUpdateResult(boolean accepted, PlayerState state) {

    public static PositionUpdateResult accepted(PlayerState state) {
        return new PositionUpdateResult(true, state);
    }

    public static PositionUpdateResult rejected(PlayerState state) {
        return new PositionUpdateResult(false, state);
    }
}
