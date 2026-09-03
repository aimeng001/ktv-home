package com.homektv.queue;

import com.homektv.domain.PlayerState;

/** Result of a TV completion report, including retry/idempotency status. */
public record FinishResult(Status status, PlayerState state, Long queueId) {

    public enum Status {
        APPLIED,
        ALREADY_APPLIED,
        STALE
    }

    public static FinishResult applied(PlayerState state, Long queueId) {
        return new FinishResult(Status.APPLIED, state, queueId);
    }

    public static FinishResult alreadyApplied(PlayerState state, Long queueId) {
        return new FinishResult(Status.ALREADY_APPLIED, state, queueId);
    }

    public static FinishResult stale(PlayerState state, Long queueId) {
        return new FinishResult(Status.STALE, state, queueId);
    }
}
