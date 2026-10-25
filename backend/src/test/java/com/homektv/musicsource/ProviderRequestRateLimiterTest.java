package com.homektv.musicsource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderRequestRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void reservesAtLeastFiveSecondsAndCountsTheActualRequest() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(0, null);
        PreparedStatement prepared = stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 500, 300);

        limiter.beforeRequest(MusicProvider.QQ);

        assertThat(state.requestCount).isEqualTo(1);
        assertThat(state.nextRequestAt).isEqualTo(NOW.plusSeconds(5));
        verify(prepared).setTimestamp(1, Timestamp.from(NOW.plusSeconds(5)));
        verify(prepared).setInt(4, 1);
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
    void resetsExpiredUtcWindowBeforeApplyingTheDailyQuota() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = new ProviderRequestRateLimiter.RateState(
                NOW.minusSeconds(1), NOW.minusSeconds(1), LocalDate.ofInstant(NOW, ZoneOffset.UTC).minusDays(1),
                1, 3, 429, "HTTP 429", NOW.minusSeconds(86_400));
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 1);

        limiter.beforeRequest(MusicProvider.QQ);

        assertThat(state.windowDateUtc).isEqualTo(LocalDate.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(state.requestCount).isEqualTo(1);
        assertThat(state.failureStreak).isZero();
        assertThat(state.lastHttpStatus).isNull();
        assertThat(state.lastError).isNull();
        assertThat(state.nextRequestAt).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void rejectsRequestsAtTheDailyQuotaUntilNextUtcMidnight() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW.minusSeconds(1));
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 1);

        assertThatThrownBy(() -> limiter.beforeRequest(MusicProvider.QQ))
                .isInstanceOf(ProviderRateLimitedException.class)
                .extracting("retryAt").isEqualTo(LocalDate.ofInstant(NOW, ZoneOffset.UTC)
                        .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(state.requestCount).isEqualTo(1);
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));
    }

    @Test
    void rejectsRequestsDuringCooldownEvenAfterTheMinimumIntervalHasPassed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW.minusSeconds(1));
        state.cooldownUntil = NOW.plusSeconds(75);
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        assertThatThrownBy(() -> limiter.beforeRequest(MusicProvider.QQ))
                .isInstanceOf(ProviderRateLimitedException.class)
                .extracting("retryAt").isEqualTo(NOW.plusSeconds(75));
        assertThat(state.requestCount).isEqualTo(1);
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
    void appliesForbiddenCooldownWithoutShorteningAnExistingLongerCooldown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW.plusSeconds(3 * 86_400L));
        state.cooldownUntil = NOW.plusSeconds(2 * 86_400L);
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        limiter.recordFailure(MusicProvider.QQ, 403, null);

        assertThat(state.failureStreak).isEqualTo(1);
        assertThat(state.lastHttpStatus).isEqualTo(403);
        assertThat(state.lastError).isEqualTo("HTTP 403");
        assertThat(state.cooldownUntil).isEqualTo(NOW.plusSeconds(2 * 86_400L));
        assertThat(state.nextRequestAt).isEqualTo(NOW.plusSeconds(3 * 86_400L));
    }

    @Test
    void enforcesTheMinimum429CooldownWhenRetryAfterIsMissingOrTooShort() {
        for (Instant retryAfter : new Instant[]{null, NOW.plusSeconds(30)}) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ProviderRequestRateLimiter.RateState state = state(1, NOW.minusSeconds(1));
            stubState(jdbc, state);
            ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                    jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

            limiter.recordFailure(MusicProvider.QQ, 429, retryAfter);

            assertThat(state.cooldownUntil).isEqualTo(NOW.plusSeconds(3_600));
            assertThat(state.nextRequestAt).isEqualTo(NOW.plusSeconds(3_600));
        }
    }

    @Test
    void transportFailureUsesBackoffAndSuccessClearsFailureDetails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(1, NOW.minusSeconds(1));
        stubState(jdbc, state);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        limiter.recordFailure(MusicProvider.QQ, null, null);

        assertThat(state.failureStreak).isEqualTo(1);
        assertThat(state.lastHttpStatus).isNull();
        assertThat(state.lastError).isEqualTo("transport failure");
        assertThat(state.cooldownUntil).isEqualTo(NOW.plusSeconds(900));

        limiter.recordSuccess(MusicProvider.QQ);

        assertThat(state.failureStreak).isZero();
        assertThat(state.lastHttpStatus).isNull();
        assertThat(state.lastError).isNull();
        assertThat(state.updatedAt).isEqualTo(NOW);
        assertThat(state.cooldownUntil).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void failsClosedWhenTheRateStateRowDisappearsDuringWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProviderRequestRateLimiter.RateState state = state(0, null);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(state);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        assertThatThrownBy(() -> limiter.beforeRequest(MusicProvider.QQ))
                .isInstanceOf(ProviderRateStateUnavailableException.class);
    }

    @Test
    void readsNullableCooldownAndWritesNullDatabaseColumnsAsSqlNull() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getTimestamp("next_request_at")).thenReturn(Timestamp.from(NOW.minusSeconds(1)));
        when(resultSet.getTimestamp("cooldown_until")).thenReturn(null);
        when(resultSet.getDate("window_date_utc")).thenReturn(Date.valueOf(LocalDate.ofInstant(NOW, ZoneOffset.UTC)));
        when(resultSet.getInt("request_count")).thenReturn(0);
        when(resultSet.getInt("failure_streak")).thenReturn(0);
        when(resultSet.getObject("last_http_status")).thenReturn(null);
        when(resultSet.getString("last_error")).thenReturn(null);
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));

        AtomicReference<ProviderRequestRateLimiter.RateState> loaded = new AtomicReference<>();
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<ProviderRequestRateLimiter.RateState> mapper = invocation.getArgument(1);
            ProviderRequestRateLimiter.RateState state = mapper.mapRow(resultSet, 0);
            loaded.set(state);
            return state;
        });
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenAnswer(invocation -> {
            PreparedStatementSetter setter = invocation.getArgument(1);
            setter.setValues(prepared);
            return 1;
        });
        ProviderRequestRateLimiter limiter = new ProviderRequestRateLimiter(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), 5_000, 300);

        limiter.beforeRequest(MusicProvider.QQ);

        assertThat(loaded.get().cooldownUntil).isNull();
        assertThat(loaded.get().requestCount).isEqualTo(1);
        verify(prepared).setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
        verify(prepared).setNull(6, Types.INTEGER);
        verify(prepared).setNull(7, Types.VARCHAR);
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

    private static PreparedStatement stubState(JdbcTemplate jdbc, ProviderRequestRateLimiter.RateState state) {
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(state);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenAnswer(invocation -> {
            PreparedStatementSetter setter = invocation.getArgument(1);
            setter.setValues(prepared);
            return 1;
        });
        return prepared;
    }
}
