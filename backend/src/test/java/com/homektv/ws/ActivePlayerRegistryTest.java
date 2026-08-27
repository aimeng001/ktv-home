package com.homektv.ws;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ActivePlayerRegistryTest {

    @Test
    void concurrentPlayersProduceExactlyOneActiveAssignment() throws Exception {
        ActivePlayerRegistry registry = new ActivePlayerRegistry(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(45));
        int playerCount = 32;
        CountDownLatch ready = new CountDownLatch(playerCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(playerCount)) {
            List<Future<ActivePlayerRegistry.Assignment>> results = new ArrayList<>();
            for (int i = 0; i < playerCount; i++) {
                int index = i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.register(
                            "session-" + index,
                            new ActivePlayerRegistry.PlayerHello(
                                    "player-" + index, "WINDOWS", 2));
                }));
            }

            ready.await();
            start.countDown();

            List<ActivePlayerRegistry.Assignment> assignments = new ArrayList<>();
            for (Future<ActivePlayerRegistry.Assignment> result : results) {
                assignments.add(result.get());
            }

            assertThat(assignments)
                    .filteredOn(assignment -> assignment.role() == ActivePlayerRegistry.Role.ACTIVE)
                    .hasSize(1);
            assertThat(registry.connectedPlayerCount()).isEqualTo(playerCount);
            assertThat(registry.activeSessionId()).isPresent();
        }
    }

    @Test
    void onlyTheActiveGenerationCanReportPlaybackState() {
        ActivePlayerRegistry registry = registry();

        ActivePlayerRegistry.Assignment active = registry.register(
                "active-session",
                new ActivePlayerRegistry.PlayerHello("active-player", "ANDROID_TV", 2));
        ActivePlayerRegistry.Assignment standby = registry.register(
                "standby-session",
                new ActivePlayerRegistry.PlayerHello("standby-player", "WINDOWS", 2));

        assertThat(registry.authorizesUpstream("active-session", active.generation())).isTrue();
        assertThat(registry.authorizesUpstream("active-session", active.generation() + 1)).isFalse();
        assertThat(registry.authorizesUpstream("standby-session", standby.generation())).isFalse();
        assertThat(registry.authorizesUpstream("missing-session", active.generation())).isFalse();
    }

    @Test
    void activeDisconnectPromotesOnlyTheOldestStandbyWithANewGeneration() {
        ActivePlayerRegistry registry = registry();
        ActivePlayerRegistry.Assignment first = registry.register(
                "first", new ActivePlayerRegistry.PlayerHello("one", "ANDROID_TV", 2));
        registry.register("second", new ActivePlayerRegistry.PlayerHello("two", "WINDOWS", 2));
        registry.register("third", new ActivePlayerRegistry.PlayerHello("three", "WINDOWS", 2));

        ActivePlayerRegistry.Promotion promotion = registry.unregister("first").orElseThrow();

        assertThat(promotion.sessionId()).isEqualTo("second");
        assertThat(promotion.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        assertThat(promotion.assignment().generation()).isGreaterThan(first.generation());
        assertThat(registry.activeSessionId()).contains("second");
        assertThat(registry.unregister("first")).isEmpty();
        assertThat(registry.activeSessionId()).contains("second");
    }

    @Test
    void expiredV2LeasePromotesStandbyAndFencesTheOldGeneration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        ActivePlayerRegistry registry = new ActivePlayerRegistry(clock, Duration.ofSeconds(45));
        ActivePlayerRegistry.Assignment first = registry.register(
                "first", new ActivePlayerRegistry.PlayerHello("one", "ANDROID_TV", 2));
        registry.register("second", new ActivePlayerRegistry.PlayerHello("two", "WINDOWS", 2));

        clock.advance(Duration.ofSeconds(46));
        assertThat(registry.authorizesUpstream("first", first.generation())).isFalse();
        ActivePlayerRegistry.Expiration expiration = registry.expireAndPromote().orElseThrow();
        ActivePlayerRegistry.Promotion promotion = expiration.promotion().orElseThrow();

        assertThat(expiration.expiredSessionId()).isEqualTo("first");
        assertThat(promotion.sessionId()).isEqualTo("second");
        assertThat(promotion.assignment().generation()).isGreaterThan(first.generation());
        assertThat(registry.authorizesUpstream("first", first.generation())).isFalse();
        assertThat(registry.activeSessionId()).contains("second");
    }

    @Test
    void onlyCurrentActiveHeartbeatRenewsTheV2Lease() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        ActivePlayerRegistry registry = new ActivePlayerRegistry(clock, Duration.ofSeconds(45));
        ActivePlayerRegistry.Assignment active = registry.register(
                "first", new ActivePlayerRegistry.PlayerHello("one", "ANDROID_TV", 2));
        registry.register("second", new ActivePlayerRegistry.PlayerHello("two", "WINDOWS", 2));

        clock.advance(Duration.ofSeconds(30));
        assertThat(registry.renew("second", 0)).isEmpty();
        assertThat(registry.renew("first", active.generation() + 1)).isEmpty();
        assertThat(registry.renew("first", active.generation())).isPresent();

        clock.advance(Duration.ofSeconds(30));
        assertThat(registry.expireAndPromote()).isEmpty();
        assertThat(registry.activeSessionId()).contains("first");
    }

    @Test
    void sameDeviceReconnectReplacesItsStaleSessionAndFencesOldGeneration() {
        ActivePlayerRegistry registry = registry();
        ActivePlayerRegistry.Assignment first = registry.register("old-session",
                new ActivePlayerRegistry.PlayerHello("stable-device", "WINDOWS", 2));

        ActivePlayerRegistry.Assignment replacement = registry.register("new-session",
                new ActivePlayerRegistry.PlayerHello("stable-device", "WINDOWS", 2));

        assertThat(replacement.role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        assertThat(replacement.generation()).isGreaterThan(first.generation());
        assertThat(registry.connectedPlayerCount()).isEqualTo(1);
        assertThat(registry.authorizesUpstream("old-session", first.generation())).isFalse();
        assertThat(registry.authorizesUpstream("new-session", replacement.generation())).isTrue();
    }

    private static ActivePlayerRegistry registry() {
        return new ActivePlayerRegistry(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(45));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
