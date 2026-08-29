package com.homektv.security;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthServiceTest {
    private AppProperties properties;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        service = new AdminAuthService(properties);
    }

    @Test
    void missingPasswordFailsClosedWithoutDisablingPublicService() {
        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.login("anything"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_NOT_CONFIGURED");
    }

    @Test
    void wrongPasswordDoesNotCreateUsableSession() {
        properties.setAdminPassword("correct-password");

        assertThatThrownBy(() -> service.login("wrong-password"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_INVALID");
    }

    @Test
    void correctPasswordCreatesRandomSessionThatCanBeLoggedOut() {
        properties.setAdminPassword("correct-password");

        String token = service.login("correct-password");

        assertThat(token).isNotBlank();
        assertThat(token).doesNotContain("correct-password");
        assertThat(service.isAuthenticated(token)).isTrue();
        service.logout(token);
        assertThat(service.isAuthenticated(token)).isFalse();
    }

    @Test
    void throttlesRepeatedFailuresPerClientAndAllowsRetryAfterCooldown() {
        properties.setAdminPassword("correct-password");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        service = new AdminAuthService(properties, clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login("wrong-password", "192.168.1.10"))
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getCode())
                    .isEqualTo("ADMIN_AUTH_INVALID");
        }
        assertThatThrownBy(() -> service.login("wrong-password", "192.168.1.10"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_RATE_LIMITED");

        assertThat(service.login("correct-password", "192.168.1.11")).isNotBlank();

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(service.login("correct-password", "192.168.1.10")).isNotBlank();
    }

    @Test
    void successfulLoginClearsTheFailureCounterForThatClient() {
        properties.setAdminPassword("correct-password");
        service = new AdminAuthService(properties, new MutableClock(Instant.parse("2026-08-30T00:00:00Z")));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> service.login("wrong-password", "192.168.1.12"))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(service.login("correct-password", "192.168.1.12")).isNotBlank();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login("wrong-password", "192.168.1.12"))
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getCode())
                    .isEqualTo("ADMIN_AUTH_INVALID");
        }
        assertThatThrownBy(() -> service.login("wrong-password", "192.168.1.12"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_RATE_LIMITED");
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
