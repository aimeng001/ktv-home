package com.homektv.musicsource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRequestRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void reservesAtLeastFiveSecondsAndCountsTheActualRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(0, null);
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 500, 300);

        limiter.beforeRequest(MusicProvider.QQ);

        assertThat(state.requestCount).isEqualTo(1);
        assertThat(state.nextRequestAt).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void rejectsTheSecondRequestUntilThePersistedIntervalExpires() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW.plusSeconds(5));
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        assertThatThrownBy(() -> limiter.beforeRequest(MusicProvider.QQ))
                .isInstanceOf(ProviderRateLimitedException.class)
                .extracting("retryAt").isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void recordsRetryAfterAndFailsClosedWhenStateCannotBeRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW);
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        Instant retryAfter = NOW.plusSeconds(7200);
        limiter.recordFailure(MusicProvider.QQ, 429, retryAfter);
        assertThat(state.cooldownUntil).isEqualTo(retryAfter);
        assertThat(state.lastHttpStatus).isEqualTo(429);

        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> limiter.beforeRequest(MusicProvider.QQ))
                .isInstanceOf(ProviderRateStateUnavailableException.class);
    }

    @Test
    void usesFiniteTransportBackoff() {
        assertThat(ProviderRequestRateLimiter.backoffSeconds(1)).isEqualTo(15 * 60L);
        assertThat(ProviderRequestRateLimiter.backoffSeconds(2)).isEqualTo(60 * 60L);
        assertThat(ProviderRequestRateLimiter.backoffSeconds(3)).isEqualTo(6 * 60 * 60L);
        assertThat(ProviderRequestRateLimiter.backoffSeconds(8)).isEqualTo(24 * 60 * 60L);
    }

    @Test
    void rateAccountingUsesIndependentTransactionsFromTheBusinessOperation() throws Exception {
        assertThat(ProviderRequestRateLimiter.class
                .getMethod("beforeRequest", MusicProvider.class)
                .getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(ProviderRequestRateLimiter.class
                .getMethod("recordSuccess", MusicProvider.class)
                .getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(ProviderRequestRateLimiter.class
                .getMethod("recordFailure", MusicProvider.class, Integer.class, Instant.class)
                .getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static ProviderRequestRateLimiter.RateState state(int count, Instant nextRequestAt) {
        return new ProviderRequestRateLimiter.RateState(
                nextRequestAt == null ? NOW.minusSeconds(1) : nextRequestAt,
                null,
                LocalDate.ofInstant(NOW, ZoneOffset.UTC),
                count, 0, null, null, NOW);
    }

    private static void stubState(JdbcTemplate jdbc, ProviderRequestRateLimiter.RateState state) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(state);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    }
}
