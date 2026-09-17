package com.homektv.discovery;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persistent identity of one Home KTV installation, stable across DHCP changes. */
@Service
public class ServerInstanceIdentityService {
    public static final String SETTING_KEY = "server_instance_id";

    /** Placeholder used by the database-free {@code /api/health} probe. */
    public static final String UNKNOWN_INSTANCE_ID = "unknown";

    private static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtext('home-ktv-server-instance'))";
    private static final String READ_SQL =
            "SELECT value #>> '{}' FROM settings WHERE key = ?";
    private static final String INSERT_SQL =
            "INSERT INTO settings(key, value) VALUES (?, to_jsonb(CAST(? AS text))) " +
                    "ON CONFLICT (key) DO NOTHING";

    private final JdbcTemplate jdbc;

    /**
     * Identity resolved once at startup so {@code /api/health} stays a lightweight
     * service-discovery probe that never waits on PostgreSQL.
     */
    private volatile String cachedInstanceId;

    public ServerInstanceIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the persisted UUID, creating it exactly once under a PostgreSQL
     * transaction lock. An invalid stored value is treated as a hard failure;
     * silently replacing an installation identity would make saved clients
     * look like a different server.
     *
     * <p>每次成功解析都会顺带预热缓存：mDNS 广播使用的是同一个身份，
     * 若 {@code /api/health} 在广播可见时仍返回 {@code unknown}，
     * 客户端会因身份不一致而丢弃该服务端。
     *
     * Every successful resolution also warms the cache. mDNS advertises this same
     * identity, and a client that sees the advertisement while {@code /api/health}
     * still answered {@code unknown} would drop the server as a mismatch.
     */
    @Transactional
    public String getOrCreate() {
        jdbc.execute(LOCK_SQL);
        String existing = readStored();
        if (isValid(existing)) {
            cache(existing);
            return existing;
        }

        String generated = UUID.randomUUID().toString();
        jdbc.update(INSERT_SQL, SETTING_KEY, generated);
        String persisted = readStored();
        if (!isValid(persisted)) {
            throw new IllegalStateException("server instance identity was not persisted");
        }
        cache(persisted);
        return persisted;
    }

    /**
     * Caches the installation identity resolved at startup. Invalid values are
     * ignored on purpose: reporting {@code unknown} is safer than announcing a
     * bogus identity that saved clients could bind to.
     */
    public void cache(String instanceId) {
        if (!isValid(instanceId)) return;
        this.cachedInstanceId = instanceId.trim();
    }

    /**
     * Identity for the lightweight {@code /api/health} probe. Never touches the
     * database; the startup warm-up is what fills the cache, and {@code /api/ready}
     * is what reports database availability.
     */
    public String cachedOrDefault() {
        String value = cachedInstanceId;
        return value == null ? UNKNOWN_INSTANCE_ID : value;
    }

    private String readStored() {
        try {
            return jdbc.queryForObject(READ_SQL, String.class, SETTING_KEY);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    private boolean isValid(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
