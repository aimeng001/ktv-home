package com.homektv.musicsource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Cross-thread and cross-restart gate for every external music-provider request.
 *
 * The state is reserved in a short database transaction before the network call.
 * A missing or unreadable state is deliberately fail-closed.
 */
@Component
public class ProviderRequestRateLimiter {
    static final long MIN_SAFE_INTERVAL_MS = 5_000L;
    static final int DEFAULT_DAILY_LIMIT = 300;
    static final long MIN_429_COOLDOWN_SECONDS = 60 * 60L;
    static final long FORBIDDEN_COOLDOWN_SECONDS = 24 * 60 * 60L;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long minIntervalMs;
    private final int dailyLimit;

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderRequestRateLimiter(
            JdbcTemplate jdbc,
            @Value("${app.music-provider-rate-limit.min-interval-ms:5000}") long configuredIntervalMs,
            @Value("${app.music-provider-rate-limit.daily-limit:300}") int configuredDailyLimit) {
        this(jdbc, Clock.systemUTC(), Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs),
                Math.max(1, configuredDailyLimit));
    }

    ProviderRequestRateLimiter(JdbcTemplate jdbc, Clock clock, long configuredIntervalMs, int configuredDailyLimit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minIntervalMs = Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs);
        this.dailyLimit = Math.max(1, configuredDailyLimit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beforeRequest(MusicProvider provider) {
        Instant now = clock.instant();
        try {
            RateState state = lock(provider);
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (!today.equals(state.windowDateUtc)) {
                state.windowDateUtc = today;
                state.requestCount = 0;
                state.failureStreak = 0;
                state.lastHttpStatus = null;
                state.lastError = null;
            }
            if (state.requestCount >= dailyLimit) {
                throw new ProviderRateLimitedException(provider, today.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            Instant blockedUntil = max(state.nextRequestAt, state.cooldownUntil);
            if (blockedUntil != null && blockedUntil.isAfter(now)) {
                throw new ProviderRateLimitedException(provider, blockedUntil);
            }
            state.requestCount++;
            state.nextRequestAt = now.plusMillis(minIntervalMs);
            state.updatedAt = now;
            save(provider, state);
        } catch (ProviderRateLimitedException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw unavailable(provider, ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(MusicProvider provider) {
        try {
            RateState state = lock(provider);
            state.failureStreak = 0;
            state.lastHttpStatus = null;
            state.lastError = null;
            state.updatedAt = clock.instant();
            save(provider, state);
        } catch (DataAccessException ex) {
            throw unavailable(provider, ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(MusicProvider provider, Integer statusCode, Instant retryAfter) {
        Instant now = clock.instant();
        try {
            RateState state = lock(provider);
            state.failureStreak++;
            state.lastHttpStatus = statusCode;
            state.lastError = statusCode == null ? "transport failure" : "HTTP " + statusCode;
            Instant cooldown;
            if (statusCode != null && statusCode == 403) {
                cooldown = now.plusSeconds(FORBIDDEN_COOLDOWN_SECONDS);
            } else if (statusCode != null && statusCode == 429) {
                Instant minimum = now.plusSeconds(MIN_429_COOLDOWN_SECONDS);
                cooldown = max(minimum, retryAfter);
            } else {
                cooldown = now.plusSeconds(backoffSeconds(state.failureStreak));
            }
            state.cooldownUntil = max(state.cooldownUntil, cooldown);
            state.nextRequestAt = max(state.nextRequestAt, state.cooldownUntil);
            state.updatedAt = now;
            save(provider, state);
        } catch (DataAccessException ex) {
            throw unavailable(provider, ex);
        }
    }

    private RateState lock(MusicProvider provider) {
        return jdbc.queryForObject("""
                SELECT next_request_at, cooldown_until, window_date_utc, request_count,
                       failure_streak, last_http_status, last_error, updated_at
                FROM music_provider_rate_state
                WHERE provider=?
                FOR UPDATE
                """, (rs, ignored) -> from(rs), provider.name());
    }

    private void save(MusicProvider provider, RateState state) {
        int updated = jdbc.update("""
                UPDATE music_provider_rate_state
                SET next_request_at=?, cooldown_until=?, window_date_utc=?,
                    request_count=?, failure_streak=?, last_http_status=?,
                    last_error=?, updated_at=?
                WHERE provider=?
                """, ps -> bind(ps, provider, state));
        if (updated != 1) {
            throw new DataAccessException("provider rate state row disappeared") {};
        }
    }

    private static void bind(PreparedStatement ps, MusicProvider provider, RateState state) throws SQLException {
        ps.setTimestamp(1, Timestamp.from(state.nextRequestAt));
        if (state.cooldownUntil == null) ps.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
        else ps.setTimestamp(2, Timestamp.from(state.cooldownUntil));
        ps.setDate(3, Date.valueOf(state.windowDateUtc));
        ps.setInt(4, state.requestCount);
        ps.setInt(5, state.failureStreak);
        if (state.lastHttpStatus == null) ps.setNull(6, Types.INTEGER);
        else ps.setInt(6, state.lastHttpStatus);
        if (state.lastError == null) ps.setNull(7, Types.VARCHAR);
        else ps.setString(7, state.lastError);
        ps.setTimestamp(8, Timestamp.from(state.updatedAt));
        ps.setString(9, provider.name());
    }

    private static RateState from(ResultSet rs) throws SQLException {
        return new RateState(
                rs.getTimestamp("next_request_at").toInstant(),
                timestamp(rs, "cooldown_until"),
                rs.getDate("window_date_utc").toLocalDate(),
                rs.getInt("request_count"),
                rs.getInt("failure_streak"),
                (Integer) rs.getObject("last_http_status"),
                rs.getString("last_error"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant timestamp(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    static long backoffSeconds(int streak) {
        return switch (Math.min(Math.max(streak, 1), 4)) {
            case 1 -> 15 * 60L;
            case 2 -> 60 * 60L;
            case 3 -> 6 * 60 * 60L;
            default -> 24 * 60 * 60L;
        };
    }

    private static Instant max(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private static ProviderRateStateUnavailableException unavailable(MusicProvider provider, Throwable cause) {
        return new ProviderRateStateUnavailableException(provider, cause);
    }

    static final class RateState {
        Instant nextRequestAt;
        Instant cooldownUntil;
        LocalDate windowDateUtc;
        int requestCount;
        int failureStreak;
        Integer lastHttpStatus;
        String lastError;
        Instant updatedAt;

        RateState(Instant nextRequestAt, Instant cooldownUntil, LocalDate windowDateUtc,
                  int requestCount, int failureStreak, Integer lastHttpStatus,
                  String lastError, Instant updatedAt) {
            this.nextRequestAt = nextRequestAt;
            this.cooldownUntil = cooldownUntil;
            this.windowDateUtc = windowDateUtc;
            this.requestCount = requestCount;
            this.failureStreak = failureStreak;
            this.lastHttpStatus = lastHttpStatus;
            this.lastError = lastError;
            this.updatedAt = updatedAt;
        }
    }
}
