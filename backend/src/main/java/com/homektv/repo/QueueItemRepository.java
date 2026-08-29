package com.homektv.repo;

import com.homektv.domain.QueueItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 点歌队列条目数据访问接口，操作 ktv_queue_item 表。
 *
 * Queue item repository for accessing the ktv_queue_item table.
 */
public interface QueueItemRepository extends JpaRepository<QueueItem, Long> {

    /**
     * 获取整个房间队列变更共用的事务级咨询锁。
     *
     * Queue ordering and playback transitions read and then rewrite shared
     * queue state.  A single PostgreSQL transaction-level lock makes those
     * read-modify-write sections mutually exclusive across application
     * instances without holding a JVM-local lock.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(2147483647)", nativeQuery = true)
    void lockQueueMutation();

    /**
     * 按指定状态查询队列条目，按排队序号升序排列。
     *
     * Query queue items by status, ordered by order index ascending.
     */
    List<QueueItem> findByStatusOrderByOrderIndexAsc(String status);

    /**
     * 按多个状态查询队列条目，按排队序号升序排列。
     *
     * Query queue items by any of the given statuses, ordered by order index ascending.
     */
    List<QueueItem> findByStatusInOrderByOrderIndexAsc(List<String> statuses);

    /**
     * 查询指定歌曲在指定状态下的首个队列条目（用于判断歌曲是否已在等待队列中）。
     *
     * Find the first queue item by song ID and status (to check if a song
     * is already in the waiting queue).
     */
    Optional<QueueItem> findFirstBySongIdAndStatus(Long songId, String status);

    /**
     * 查询指定状态下排队序号最大的队列条目（用于追加队尾）。
     *
     * Find the queue item with the highest order index in the given status
     * (for appending to the queue tail).
     */
    Optional<QueueItem> findFirstByStatusOrderByOrderIndexDesc(String status);

    long countByStatus(String status);

    List<QueueItem> findBySongId(Long songId);

    /**
     * 对指定歌曲ID获取 PostgreSQL 会话级咨询锁，用于并发安全的点歌排序操作。
     *
     * Acquire a PostgreSQL session-level advisory lock for the given song ID,
     * used for concurrency-safe queue ordering.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:songId)", nativeQuery = true)
    void lockSongForOrder(Long songId);
}
