package com.homektv.musicsource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-thread and cross-restart gate for every external music-provider request.
 *
 * The state is reserved in a short database transaction before the network call.
 * A missing or unreadable state is deliberately fail-closed.
 */
@Component
public class ProviderRequestRateLimiter {
    static final long MIN_SAFE_INTERVAL_MS = 1_500L;
    static final int DEFAULT_DAILY_LIMIT = 3_000;
    static final long MIN_429_COOLDOWN_SECONDS = 15 * 60L;
    static final long FORBIDDEN_COOLDOWN_SECONDS = 6 * 60 * 60L;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long minIntervalMs;
    private final int dailyLimit;
    private final MusicSourceConfigService configService;

    @Autowired
    public ProviderRequestRateLimiter(
            JdbcTemplate jdbc,
            @Value("${app.music-provider-rate-limit.min-interval-ms:1500}") long configuredIntervalMs,
            @Value("${app.music-provider-rate-limit.daily-limit:3000}") int configuredDailyLimit,
            MusicSourceConfigService configService) {
        this(jdbc, Clock.systemUTC(), Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs),
                Math.max(1, configuredDailyLimit), configService);
    }

    public ProviderRequestRateLimiter(
            JdbcTemplate jdbc,
            long configuredIntervalMs,
            int configuredDailyLimit) {
        this(jdbc, Clock.systemUTC(), Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs),
                Math.max(1, configuredDailyLimit), null);
    }

    ProviderRequestRateLimiter(JdbcTemplate jdbc, Clock clock, long configuredIntervalMs, int configuredDailyLimit) {
        this(jdbc, clock, configuredIntervalMs, configuredDailyLimit, null);
    }

    ProviderRequestRateLimiter(JdbcTemplate jdbc, Clock clock, long configuredIntervalMs, int configuredDailyLimit,
                               MusicSourceConfigService configService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minIntervalMs = Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs);
        this.dailyLimit = Math.max(1, configuredDailyLimit);
        this.configService = configService;
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Fast non-mutating check so cooled-down or exhausted providers fail before waiting locally. */
    public void ensureNotBlocked(MusicProvider provider) {
        Instant now = clock.instant();
        try {
            List<RateState> states = jdbc.query("""
                    SELECT next_request_at, cooldown_until, window_date_utc, request_count,
                           failure_streak, last_http_status, last_error, updated_at
                    FROM music_provider_rate_state
                    WHERE provider=?
                    """, (rs, ignored) -> from(rs), provider.name());
            if (states.isEmpty()) return;
            RateState state = states.getFirst();
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (!today.equals(state.windowDateUtc)) {
                return;
            }
            int effectiveLimit = effectiveDailyLimit();
            if (state.requestCount >= effectiveLimit) {
                throw new ProviderRateLimitedException(provider, today.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            if (state.cooldownUntil != null && state.cooldownUntil.isAfter(now)) {
                throw new ProviderRateLimitedException(provider, state.cooldownUntil);
            }
        } catch (ProviderRateLimitedException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw unavailable(provider, ex);
        }
    }

    /** Non-locking snapshot used by the avatar router to pick immediately available providers first. */
    public Map<MusicProvider, RateSnapshot> snapshotStates() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Map<MusicProvider, RateSnapshot> snapshots = new EnumMap<>(MusicProvider.class);
        try {
            int effectiveLimit = effectiveDailyLimit();
            jdbc.query("""
                    SELECT provider, next_request_at, cooldown_until, window_date_utc, request_count
                    FROM music_provider_rate_state
                    """, rs -> {
                String code = rs.getString("provider");
                MusicProvider provider;
                try {
                    provider = MusicProvider.parse(code);
                } catch (RuntimeException ignored) {
                    return;
                }
                Timestamp nextTs = rs.getTimestamp("next_request_at");
                Instant nextAt = nextTs == null ? now : nextTs.toInstant();
                Instant cooldownUntil = timestamp(rs, "cooldown_until");
                Date windowDate = rs.getDate("window_date_utc");
                LocalDate date = windowDate == null ? today : windowDate.toLocalDate();
                int count = date.equals(today) ? rs.getInt("request_count") : 0;
                Instant blockedUntil = null;
                if (count >= effectiveLimit) {
                    blockedUntil = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                } else if (cooldownUntil != null && cooldownUntil.isAfter(now)) {
                    blockedUntil = cooldownUntil;
                }
                snapshots.put(provider, new RateSnapshot(
                        blockedUntil == null,
                        max(nextAt, cooldownUntil),
                        blockedUntil));
            });
        } catch (DataAccessException ignored) {
            return Map.of();
        }
        return snapshots;
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
            int effectiveLimit = effectiveDailyLimit();
            if (state.requestCount >= effectiveLimit) {
                throw new ProviderRateLimitedException(provider, today.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            Instant blockedUntil = max(state.nextRequestAt, state.cooldownUntil);
            if (blockedUntil != null && blockedUntil.isAfter(now)) {
                throw new ProviderRateLimitedException(provider, blockedUntil);
            }
            long effectiveIntervalMs = effectiveIntervalMs();
            state.requestCount++;
            state.nextRequestAt = now.plusMillis(effectiveIntervalMs);
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
            if (statusCode != null && statusCode == 403) {
                state.cooldownUntil = max(state.cooldownUntil, now.plusSeconds(FORBIDDEN_COOLDOWN_SECONDS));
                state.nextRequestAt = max(state.nextRequestAt, state.cooldownUntil);
            } else if (statusCode != null && statusCode == 429) {
                Instant minimum = now.plusSeconds(MIN_429_COOLDOWN_SECONDS);
                state.cooldownUntil = max(state.cooldownUntil, max(minimum, retryAfter));
                state.nextRequestAt = max(state.nextRequestAt, state.cooldownUntil);
            } else {
                long delaySeconds = backoffSeconds(state.failureStreak);
                if (delaySeconds > 0) {
                    state.cooldownUntil = max(state.cooldownUntil, now.plusSeconds(delaySeconds));
                    state.nextRequestAt = max(state.nextRequestAt, state.cooldownUntil);
                }
            }
            state.updatedAt = now;
            save(provider, state);
        } catch (DataAccessException ex) {
            throw unavailable(provider, ex);
        }
    }

    /** Clears error cooldowns and failure streaks so recovered/upgraded providers can resume immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetCooldowns() {
        Instant now = clock.instant();
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
                UPDATE music_provider_rate_state
                SET cooldown_until = NULL,
                    failure_streak = 0,
                    last_http_status = NULL,
                    last_error = NULL,
                    next_request_at = ?,
                    updated_at = ?
                """, ts, ts);
    }

    private int effectiveDailyLimit() {
        MusicSourceConfig config = configService != null ? configService.getConfig() : null;
        return config != null && config.dailyLimit() > 0 ? config.dailyLimit() : dailyLimit;
    }

    private long effectiveIntervalMs() {
        MusicSourceConfig config = configService != null ? configService.getConfig() : null;
        long configured = config != null ? config.requestIntervalMs() : 0L;
        return Math.max(minIntervalMs, configured);
    }

    private RateState lock(MusicProvider provider) {
        try {
            return jdbc.queryForObject("""
                    SELECT next_request_at, cooldown_until, window_date_utc, request_count,
                           failure_streak, last_http_status, last_error, updated_at
                    FROM music_provider_rate_state
                    WHERE provider=?
                    FOR UPDATE
                    """, (rs, ignored) -> from(rs), provider.name());
        } catch (EmptyResultDataAccessException missing) {
            jdbc.update("""
                    INSERT INTO music_provider_rate_state(provider)
                    VALUES (?)
                    ON CONFLICT (provider) DO NOTHING
                    """, provider.name());
            return jdbc.queryForObject("""
                    SELECT next_request_at, cooldown_until, window_date_utc, request_count,
                           failure_streak, last_http_status, last_error, updated_at
                    FROM music_provider_rate_state
                    WHERE provider=?
                    FOR UPDATE
                    """, (rs, ignored) -> from(rs), provider.name());
        }
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
        if (streak < 3) return 0L;
        if (streak <= 4) return 2 * 60L;
        if (streak <= 7) return 10 * 60L;
        return 30 * 60L;
    }

    private static Instant max(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private static ProviderRateStateUnavailableException unavailable(MusicProvider provider, Throwable cause) {
        return new ProviderRateStateUnavailableException(provider, cause);
    }

    public record RateSnapshot(boolean availableNow, Instant nextAvailableAt, Instant blockedUntil) {}

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
