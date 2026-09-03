package com.homektv.repo;

import com.homektv.domain.PlayHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 点唱历史数据访问层，操作 play_history 表。
 *
 * Play history data access layer, operating on the play_history table.
 */
public interface PlayHistoryRepository extends JpaRepository<PlayHistory, Long> {

    /**
     * 点唱排行：按时间窗口统计各歌曲播放次数，返回 [songId, cnt] 降序。
     *
     * Song ranking: counts play times per song within a time window,
     * returning [songId, cnt] in descending order.
     *
     * @param since 统计起始时间 / the start time for the statistics window
     * @param limit 返回结果数量上限 / maximum number of results to return
     * @return [songId, cnt] 二维数组，按 cnt 降序 / a list of [songId, cnt] arrays in descending order by cnt
     */
    @Query(value = """
            SELECT song_id, COUNT(*) AS cnt
            FROM play_history
            WHERE played_at >= :since
            GROUP BY song_id
            ORDER BY cnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> ranking(@Param("since") OffsetDateTime since, @Param("limit") int limit);

    /**
     * 最近播放历史（今晚已唱），按时间倒序返回前 50 条。
     *
     * Recent play history (songs sung tonight), top 50 in reverse chronological order.
     *
     * @return 最近 50 条播放记录 / the most recent 50 play history entries
     */
    List<PlayHistory> findTop50ByOrderByPlayedAtDesc();

    List<PlayHistory> findTop50ByPlayedAtGreaterThanEqualOrderByPlayedAtDesc(OffsetDateTime since);

    List<PlayHistory> findTop50ByPlayedByAndPlayedAtGreaterThanEqualOrderByPlayedAtDesc(
            Long playedBy, OffsetDateTime since);

    boolean existsByQueueId(Long queueId);

    void deleteBySongId(Long songId);
}
