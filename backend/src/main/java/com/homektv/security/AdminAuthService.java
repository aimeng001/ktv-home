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

    private final AppProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

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
        if (!isConfigured()) {
            throw new ApiException("ADMIN_AUTH_NOT_CONFIGURED", "管理员密码尚未配置，请设置 KTV_ADMIN_PASSWORD");
        }
        byte[] expected = properties.getAdminPassword().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ApiException("ADMIN_AUTH_INVALID", "管理员密码错误");
        }

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        sessions.put(token, Instant.now(clock).plus(SESSION_LIFETIME));
        return token;
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
}
