package com.homektv.repo;

import com.homektv.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * AppUser 数据访问接口，对应 users 表，提供用户相关的数据库操作。
 *
 * Data access interface for AppUser, mapped to the users table,
 * providing user-related database operations.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByClientToken(String clientToken);

    long countByClientTokenNotAndNickname(String clientToken, String nickname);

    long countByClientTokenNotAndNicknameStartingWith(String clientToken, String prefix);

    /**
     * 若用户不存在则插入，利用 PostgreSQL 的 ON CONFLICT DO NOTHING 避免重复。
     *
     * Insert a user if absent; uses PostgreSQL ON CONFLICT DO NOTHING to avoid duplicates.
     */
    @Modifying
    @Query(value = "INSERT INTO users (client_token, nickname) VALUES (:clientToken, :nickname) " +
            "ON CONFLICT (client_token) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(String clientToken, String nickname);

    /**
     * 获取事务级排他锁，防止并发争抢房主角色。
     *
     * Acquire a transaction-level exclusive advisory lock to prevent concurrent race for the room host role.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('home-ktv-room-host'))", nativeQuery = true)
    void lockRoomHost();
}
