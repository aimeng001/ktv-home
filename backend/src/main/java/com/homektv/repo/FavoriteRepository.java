package com.homektv.repo;

import com.homektv.domain.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 收藏数据访问接口，操作 favorites 表。
 *
 * Favorite data access interface, operating on the "favorites" table.
 */
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    List<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId);
    Page<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    boolean existsByUserIdAndSongId(Long userId, Long songId);
    @Modifying
    @Query(value = "INSERT INTO favorites (user_id, song_id) VALUES (:userId, :songId) " +
            "ON CONFLICT (user_id, song_id) DO NOTHING", nativeQuery = true)
    /**
     * 插入收藏记录，若已存在则忽略。
     *
     * Insert a favorite record, or do nothing if it already exists.
     */
    int insertIfAbsent(Long userId, Long songId);
    long deleteByUserIdAndSongId(Long userId, Long songId);
}
