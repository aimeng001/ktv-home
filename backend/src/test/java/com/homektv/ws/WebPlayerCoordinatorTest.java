package com.homektv.ws;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WebPlayerCoordinatorTest {

    @Test
    void firstWebSessionBecomesActiveAndSecondBecomesStandbyUnlessForced() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        WebPlayerCoordinator coordinator = new WebPlayerCoordinator(clock, Duration.ofSeconds(45));

        WebPlayerCoordinator.ClaimOutcome first = coordinator.claim("h5-1", "pc-kiosk", false, false);
        assertThat(first.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        assertThat(first.assignment().generation()).isEqualTo(1L);
        assertThat(first.status()).isEqualTo(new WebPlayerCoordinator.WebPlayerStatus(true, "pc-kiosk"));

        WebPlayerCoordinator.ClaimOutcome second = coordinator.claim("h5-2", "phone-1", false, false);
        assertThat(second.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.STANDBY);
        assertThat(second.replacedSessionId()).isEmpty();
        assertThat(second.status()).isEqualTo(new WebPlayerCoordinator.WebPlayerStatus(true, "pc-kiosk"));

        WebPlayerCoordinator.ClaimOutcome forced = coordinator.claim("h5-2", "phone-1", true, false);
        assertThat(forced.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        assertThat(forced.assignment().generation()).isGreaterThan(first.assignment().generation());
        assertThat(forced.replacedSessionId()).contains("h5-1");
        assertThat(coordinator.authorizesUpstream("h5-1", first.assignment().generation())).isFalse();
        assertThat(coordinator.authorizesUpstream("h5-2", forced.assignment().generation())).isTrue();
    }

    @Test
    void physicalTvOnlineBlocksClaimAndPreemptsExistingWebPlayer() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        WebPlayerCoordinator coordinator = new WebPlayerCoordinator(clock, Duration.ofSeconds(45));

        WebPlayerCoordinator.ClaimOutcome active = coordinator.claim("h5-1", "pc-kiosk", false, false);
        assertThat(active.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);

        assertThat(coordinator.clearForTvPreemption()).contains("h5-1");
        assertThat(coordinator.authorizesUpstream("h5-1", active.assignment().generation())).isFalse();

        WebPlayerCoordinator.ClaimOutcome deniedWhileTvOnline = coordinator.claim("h5-2", "pc-2", true, true);
        assertThat(deniedWhileTvOnline.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.STANDBY);
        assertThat(deniedWhileTvOnline.status()).isEqualTo(new WebPlayerCoordinator.WebPlayerStatus(false, null));
    }

    @Test
    void expiredLeaseStopsAuthorizationAndHeartbeatRenewsActiveLease() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        WebPlayerCoordinator coordinator = new WebPlayerCoordinator(clock, Duration.ofSeconds(45));

        WebPlayerCoordinator.ClaimOutcome active = coordinator.claim("h5-1", "pc-kiosk", false, false);
        long gen = active.assignment().generation();
        clock.advance(Duration.ofSeconds(30));
        assertThat(coordinator.heartbeat("h5-1", gen)).isPresent();

        clock.advance(Duration.ofSeconds(30));
        assertThat(coordinator.authorizesUpstream("h5-1", gen)).isTrue();

        clock.advance(Duration.ofSeconds(20));
        assertThat(coordinator.authorizesUpstream("h5-1", gen)).isFalse();
    }

    @Test
    void sameClientTokenReclaimsActiveWithoutForceOnReconnect() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        WebPlayerCoordinator coordinator = new WebPlayerCoordinator(clock, Duration.ofSeconds(45));

        WebPlayerCoordinator.ClaimOutcome first = coordinator.claim("h5-1", "pc-kiosk", false, false);
        assertThat(first.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        long firstGen = first.assignment().generation();

        // 模拟同一台电脑刷新或重连（换了新的 session ID，但保持相同的 clientToken），force 为 false
        WebPlayerCoordinator.ClaimOutcome reconnected = coordinator.claim("h5-2", "pc-kiosk", false, false);
        assertThat(reconnected.assignment().role()).isEqualTo(ActivePlayerRegistry.Role.ACTIVE);
        assertThat(reconnected.assignment().generation()).isGreaterThan(firstGen);
        assertThat(reconnected.replacedSessionId()).contains("h5-1");
        assertThat(coordinator.authorizesUpstream("h5-1", firstGen)).isFalse();
        assertThat(coordinator.authorizesUpstream("h5-2", reconnected.assignment().generation())).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
