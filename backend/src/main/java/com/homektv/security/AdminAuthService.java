package com.homektv.security;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理后台的轻量会话认证。
 *
 * The admin UI is intentionally separate from the public household control
 * flow. Sessions are in-memory so no password/token is persisted in the DB.
 */
@Service
public class AdminAuthService {
    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final Duration LOGIN_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_CLIENTS = 4096;

    private final AppProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, FailedLoginState> failedLogins = new ConcurrentHashMap<>();

    @Autowired
    public AdminAuthService(AppProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AdminAuthService(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isConfigured() {
        String password = properties.getAdminPassword();
        return password != null && !password.isBlank();
    }

    public String login(String password) {
        return login(password, "direct");
    }

    public String login(String password, String clientKey) {
        if (!isConfigured()) {
            throw new ApiException("ADMIN_AUTH_NOT_CONFIGURED", "管理员密码尚未配置，请设置 KTV_ADMIN_PASSWORD");
        }
        String key = normalizeClientKey(clientKey);
        Instant now = Instant.now(clock);
        enforceCooldown(key, now);

        byte[] expected = properties.getAdminPassword().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            recordFailedLogin(key, now);
            throw new ApiException("ADMIN_AUTH_INVALID", "管理员密码错误");
        }

        failedLogins.remove(key);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        sessions.put(token, now.plus(SESSION_LIFETIME));
        return token;
    }

    private void enforceCooldown(String key, Instant now) {
        FailedLoginState state = failedLogins.get(key);
        if (state == null) return;
        if (state.blockedUntil() != null && now.isBefore(state.blockedUntil())) {
            throw new ApiException("ADMIN_AUTH_RATE_LIMITED", "登录尝试过多，请稍后再试");
        }
        if (state.blockedUntil() != null || !now.isBefore(state.windowStartedAt().plus(FAILURE_WINDOW))) {
            failedLogins.remove(key, state);
        }
    }

    private void recordFailedLogin(String key, Instant now) {
        evictExpiredClients(now);
        failedLogins.compute(key, (ignored, current) -> {
            if (current == null
                    || current.blockedUntil() != null
                    || !now.isBefore(current.windowStartedAt().plus(FAILURE_WINDOW))) {
                return new FailedLoginState(1, now, null);
            }
            int failures = current.failures() + 1;
            Instant blockedUntil = failures >= MAX_FAILED_ATTEMPTS
                    ? now.plus(LOGIN_COOLDOWN) : null;
            return new FailedLoginState(failures, current.windowStartedAt(), blockedUntil);
        });
    }

    private void evictExpiredClients(Instant now) {
        if (failedLogins.size() < MAX_TRACKED_CLIENTS) return;
        failedLogins.entrySet().removeIf(entry -> {
            FailedLoginState state = entry.getValue();
            return state.blockedUntil() != null && !now.isBefore(state.blockedUntil())
                    || !now.isBefore(state.windowStartedAt().plus(FAILURE_WINDOW));
        });
    }

    private static String normalizeClientKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) return "unknown";
        return clientKey.trim();
    }

    public boolean isAuthenticated(String token) {
        if (!isConfigured() || token == null || token.isBlank()) return false;
        Instant expiresAt = sessions.get(token);
        if (expiresAt == null) return false;
        if (!Instant.now(clock).isBefore(expiresAt)) {
            sessions.remove(token, expiresAt);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) sessions.remove(token);
    }

    public long sessionLifetimeSeconds() {
        return SESSION_LIFETIME.toSeconds();
    }

    private record FailedLoginState(int failures, Instant windowStartedAt, Instant blockedUntil) {}
}
