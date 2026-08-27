package com.homektv.ws;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Owns the single active playback projection among connected TV clients. */
@Component
public class ActivePlayerRegistry {

    public enum Role { ACTIVE, STANDBY }

    public record PlayerHello(String clientToken, String platform, int protocolVersion) {}

    public record Assignment(Role role, long generation, long leaseMs) {}

    public record Promotion(String sessionId, Assignment assignment) {}

    public record Expiration(String expiredSessionId, Optional<Promotion> promotion) {}

    public record Unregistration(boolean removed, Optional<Promotion> promotion) {}

    private record PlayerSession(
            String sessionId,
            PlayerHello hello,
            long registrationOrder) {}

    private record ActiveLease(
            String sessionId,
            long generation,
            Instant expiresAt) {}

    private final Clock clock;
    private final Duration leaseDuration;
    private final Map<String, PlayerSession> players = new LinkedHashMap<>();
    private ActiveLease active;
    private long generation;
    private long registrationOrder;

    public ActivePlayerRegistry() {
        this(Clock.systemUTC(), Duration.ofSeconds(45));
    }

    ActivePlayerRegistry(Clock clock, Duration leaseDuration) {
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public synchronized Assignment register(String sessionId, PlayerHello hello) {
        PlayerSession stale = players.values().stream()
                .filter(existing -> existing.hello().clientToken().equals(hello.clientToken()))
                .findFirst().orElse(null);
        boolean replacesActive = stale != null && active != null
                && active.sessionId().equals(stale.sessionId());
        if (stale != null) {
            players.remove(stale.sessionId());
        }
        PlayerSession player = new PlayerSession(sessionId, hello,
                stale == null ? ++registrationOrder : stale.registrationOrder());
        players.put(sessionId, player);
        if (active == null || replacesActive) {
            active = activate(player, clock.instant());
        }
        return assignmentFor(sessionId);
    }

    public synchronized int connectedPlayerCount() {
        return players.size();
    }

    public synchronized Optional<String> activeSessionId() {
        if (active == null) {
            return Optional.empty();
        }
        PlayerSession player = players.get(active.sessionId());
        if (player != null && player.hello().protocolVersion() >= 2
                && !clock.instant().isBefore(active.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(active.sessionId());
    }

    public synchronized Optional<Promotion> unregister(String sessionId) {
        return unregisterPlayer(sessionId).promotion();
    }

    /** Atomically removes a session and reports whether this was the first close callback. */
    public synchronized Unregistration unregisterPlayer(String sessionId) {
        PlayerSession removed = players.remove(sessionId);
        if (removed == null || active == null || !active.sessionId().equals(sessionId)) {
            return new Unregistration(removed != null, Optional.empty());
        }

        active = null;
        return new Unregistration(true, promoteOldest(clock.instant()));
    }

    public synchronized Optional<Expiration> expireAndPromote() {
        if (active == null) {
            return Optional.empty();
        }
        PlayerSession current = players.get(active.sessionId());
        if (current == null || current.hello().protocolVersion() < 2
                || !clock.instant().isAfter(active.expiresAt())) {
            return Optional.empty();
        }

        String expiredSessionId = active.sessionId();
        players.remove(expiredSessionId);
        active = null;
        return Optional.of(new Expiration(expiredSessionId, promoteOldest(clock.instant())));
    }

    public synchronized boolean authorizesUpstream(String sessionId, Long messageGeneration) {
        if (active == null || !active.sessionId().equals(sessionId)) {
            return false;
        }
        PlayerSession player = players.get(sessionId);
        if (player == null) {
            return false;
        }
        if (player.hello().protocolVersion() < 2) {
            return true;
        }
        return messageGeneration != null
                && active.generation() == messageGeneration
                && clock.instant().isBefore(active.expiresAt());
    }

    public synchronized Optional<Assignment> renew(String sessionId, long messageGeneration) {
        if (active == null
                || !active.sessionId().equals(sessionId)
                || active.generation() != messageGeneration) {
            return Optional.empty();
        }
        PlayerSession player = players.get(sessionId);
        Instant now = clock.instant();
        if (player == null || player.hello().protocolVersion() < 2
                || !now.isBefore(active.expiresAt())) {
            return Optional.empty();
        }

        active = new ActiveLease(sessionId, active.generation(), now.plus(leaseDuration));
        return Optional.of(assignmentFor(sessionId));
    }

    /**
     * Handles an application heartbeat. Standby and legacy clients receive
     * their current assignment; only the current protocol-v2 active player can
     * renew the lease.
     */
    public synchronized Optional<Assignment> heartbeat(String sessionId, Long messageGeneration) {
        PlayerSession player = players.get(sessionId);
        if (player == null) {
            return Optional.empty();
        }
        if (active == null || !active.sessionId().equals(sessionId)) {
            return Optional.of(assignmentFor(sessionId));
        }
        if (player.hello().protocolVersion() < 2) {
            return Optional.of(assignmentFor(sessionId));
        }
        if (messageGeneration == null) {
            return Optional.empty();
        }
        return renew(sessionId, messageGeneration);
    }

    private ActiveLease activate(PlayerSession player, Instant now) {
        return new ActiveLease(player.sessionId(), ++generation, now.plus(leaseDuration));
    }

    private Optional<Promotion> promoteOldest(Instant now) {
        PlayerSession next = players.values().stream()
                .min(java.util.Comparator.comparingLong(PlayerSession::registrationOrder))
                .orElse(null);
        if (next == null) {
            return Optional.empty();
        }
        active = activate(next, now);
        return Optional.of(new Promotion(next.sessionId(), assignmentFor(next.sessionId())));
    }

    private Assignment assignmentFor(String sessionId) {
        if (active != null && active.sessionId().equals(sessionId)) {
            return new Assignment(Role.ACTIVE, active.generation(), leaseDuration.toMillis());
        }
        return new Assignment(Role.STANDBY, 0, leaseDuration.toMillis());
    }
}
