package com.homektv.ws;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Coordinates the single active browser Web KTV player when no physical TV player is online.
 * Physical TV sessions in {@link ActivePlayerRegistry} always preempt any active web player.
 */
@Component
public class WebPlayerCoordinator {

    public record WebPlayerStatus(boolean active, String holderToken) {}

    public record ClaimOutcome(
            ActivePlayerRegistry.Assignment assignment,
            Optional<String> replacedSessionId,
            WebPlayerStatus status,
            boolean stateChanged) {}

    public record ReleaseOutcome(boolean released, WebPlayerStatus status) {}

    public record HeartbeatOutcome(
            Optional<ActivePlayerRegistry.Assignment> assignment,
            boolean expiredCleared,
            WebPlayerStatus status) {}

    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(45);

    private final Clock clock;
    private final Duration leaseDuration;
    private String activeSessionId;
    private String activeClientToken;
    private long generation;
    private Instant expiresAt;

    public WebPlayerCoordinator() {
        this(Clock.systemUTC(), DEFAULT_LEASE);
    }

    WebPlayerCoordinator(Clock clock, Duration leaseDuration) {
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    /**
     * Attempts to claim or renew the single web player lease.
     * Fails closed to {@code STANDBY} whenever a physical TV is online.
     */
    public synchronized ClaimOutcome claim(String sessionId, String clientToken,
                                           boolean force, boolean tvOnline) {
        Instant now = clock.instant();
        if (tvOnline) {
            boolean changed = clearActive();
            return new ClaimOutcome(
                    new ActivePlayerRegistry.Assignment(ActivePlayerRegistry.Role.STANDBY, 0, leaseDuration.toMillis()),
                    Optional.empty(),
                    new WebPlayerStatus(false, null),
                    changed);
        }

        boolean expiredCleared = expireIfElapsed(now);
        String normalizedToken = normalizeToken(clientToken, sessionId);
        if (activeSessionId == null || activeSessionId.equals(sessionId)) {
            boolean newlyActivated = activeSessionId == null;
            if (newlyActivated) {
                ++generation;
            }
            activeSessionId = sessionId;
            activeClientToken = normalizedToken;
            expiresAt = now.plus(leaseDuration);
            return new ClaimOutcome(
                    new ActivePlayerRegistry.Assignment(
                            ActivePlayerRegistry.Role.ACTIVE, generation, leaseDuration.toMillis()),
                    Optional.empty(),
                    new WebPlayerStatus(true, activeClientToken),
                    newlyActivated || expiredCleared);
        }

        boolean sameClientReconnect = normalizedToken.equals(activeClientToken);
        if (!force && !sameClientReconnect) {
            return new ClaimOutcome(
                    new ActivePlayerRegistry.Assignment(
                            ActivePlayerRegistry.Role.STANDBY, 0, leaseDuration.toMillis()),
                    Optional.empty(),
                    new WebPlayerStatus(true, activeClientToken),
                    expiredCleared);
        }

        String replaced = activeSessionId;
        activeSessionId = sessionId;
        activeClientToken = normalizedToken;
        ++generation;
        expiresAt = now.plus(leaseDuration);
        return new ClaimOutcome(
                new ActivePlayerRegistry.Assignment(
                        ActivePlayerRegistry.Role.ACTIVE, generation, leaseDuration.toMillis()),
                Optional.ofNullable(replaced),
                new WebPlayerStatus(true, activeClientToken),
                true);
    }

    /** Releases the active web player slot if held by {@code sessionId}. */
    public synchronized ReleaseOutcome release(String sessionId) {
        boolean expiredCleared = expireIfElapsed(clock.instant());
        if (activeSessionId != null && activeSessionId.equals(sessionId)) {
            clearActive();
            return new ReleaseOutcome(true, new WebPlayerStatus(false, null));
        }
        return new ReleaseOutcome(expiredCleared, currentStatusUnsafe());
    }

    /** Clears any active web player because a physical TV connected or resumed activity. */
    public synchronized Optional<String> clearForTvPreemption() {
        String previous = activeSessionId;
        clearActive();
        return Optional.ofNullable(previous);
    }

    /** Authorizes upstream progress/finished/play_error reports from the active web player. */
    public synchronized boolean authorizesUpstream(String sessionId, Long messageGeneration) {
        Instant now = clock.instant();
        if (expireIfElapsed(now)) {
            return false;
        }
        return activeSessionId != null
                && activeSessionId.equals(sessionId)
                && messageGeneration != null
                && generation == messageGeneration;
    }

    /** Renews the active web player lease during application ping heartbeats. */
    public synchronized Optional<ActivePlayerRegistry.Assignment> heartbeat(
            String sessionId, Long messageGeneration) {
        return heartbeatOutcome(sessionId, messageGeneration, false).assignment();
    }

    /**
     * Renews the active web player lease and exposes whether an expired holder was cleared
     * so callers can immediately demote stale browser hosts and notify room observers.
     */
    public synchronized HeartbeatOutcome heartbeatOutcome(
            String sessionId, Long messageGeneration, boolean tvOnline) {
        if (tvOnline) {
            boolean cleared = clearActive();
            return new HeartbeatOutcome(Optional.empty(), cleared, new WebPlayerStatus(false, null));
        }
        Instant now = clock.instant();
        boolean expiredCleared = expireIfElapsed(now);
        if (expiredCleared
                || activeSessionId == null
                || !activeSessionId.equals(sessionId)
                || messageGeneration == null
                || generation != messageGeneration) {
            return new HeartbeatOutcome(Optional.empty(), expiredCleared, currentStatusUnsafe());
        }
        expiresAt = now.plus(leaseDuration);
        return new HeartbeatOutcome(
                Optional.of(new ActivePlayerRegistry.Assignment(
                        ActivePlayerRegistry.Role.ACTIVE, generation, leaseDuration.toMillis())),
                false,
                currentStatusUnsafe());
    }

    /** Returns the current room-wide web player status. */
    public synchronized WebPlayerStatus status(boolean tvOnline) {
        if (tvOnline) {
            return new WebPlayerStatus(false, null);
        }
        expireIfElapsed(clock.instant());
        return currentStatusUnsafe();
    }

    private boolean expireIfElapsed(Instant now) {
        if (activeSessionId != null && expiresAt != null && !now.isBefore(expiresAt)) {
            clearActive();
            return true;
        }
        return false;
    }

    private boolean clearActive() {
        boolean hadActive = activeSessionId != null;
        activeSessionId = null;
        activeClientToken = null;
        expiresAt = null;
        return hadActive;
    }

    private WebPlayerStatus currentStatusUnsafe() {
        return activeSessionId == null
                ? new WebPlayerStatus(false, null)
                : new WebPlayerStatus(true, activeClientToken);
    }

    private static String normalizeToken(String clientToken, String fallback) {
        return clientToken == null || clientToken.isBlank() ? fallback : clientToken.trim();
    }
}
