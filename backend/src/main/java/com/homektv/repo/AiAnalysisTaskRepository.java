package com.homektv.repo;

import com.homektv.domain.AiAnalysisTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

/**
 * AI分析任务数据访问层，对应 ai_analysis_task 表。
 *
 * AI analysis task repository, mapping to the ai_analysis_task table.
 */
public interface AiAnalysisTaskRepository extends JpaRepository<AiAnalysisTask, Long> {
    interface BatchStatusCountProjection {
        String getStatus();
        Long getTaskCount();
    }

    List<AiAnalysisTask> findTop500ByStatusInAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
            List<String> statuses, OffsetDateTime createdAtCutoff);
    List<AiAnalysisTask> findTop100ByStatusOrderByCreatedAtAscIdAsc(String status);
    List<AiAnalysisTask> findTop100ByOrderByCreatedAtDesc();
    Optional<AiAnalysisTask> findFirstByBatchIdIsNotNullOrderByCreatedAtDescIdDesc();
    List<AiAnalysisTask> findByBatchIdOrderByCreatedAtAsc(String batchId);
    boolean existsBySongIdAndStatusIn(Long songId, List<String> statuses);
    Optional<AiAnalysisTask> findFirstBySongIdAndStatusInOrderByCreatedAtDesc(Long songId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AiAnalysisTask task where task.id = :taskId")
    Optional<AiAnalysisTask> findByIdForUpdate(@Param("taskId") Long taskId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task SET task.status = 'paused'
            WHERE task.batchId = :batchId AND task.status IN ('pending', 'processing')
            """)
    int pauseBatch(@Param("batchId") String batchId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task SET task.status = 'cancelled'
            WHERE task.batchId = :batchId AND task.status IN ('pending', 'paused', 'processing')
            """)
    int cancelBatch(@Param("batchId") String batchId);
    /**
     * Atomically claims a pending task. A concurrent worker that observes the
     * same task receives zero rows and must not execute the provider call.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task
            SET task.status = 'processing',
                task.attemptCount = task.attemptCount + 1,
                task.errorMessage = NULL
            WHERE task.id = :taskId AND task.status = 'pending'
            """)
    int claimPending(@Param("taskId") Long taskId);

    /**
     * Publishes a worker result only while the worker still owns the processing
     * state. A pause that wins the race cannot be overwritten by a stale entity.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task
            SET task.resultJson = :resultJson,
                task.fieldConfidence = :fieldConfidence,
                task.evidence = :evidence,
                task.modelRole = :modelRole,
                task.model = :model,
                task.status = :status,
                task.errorMessage = :errorMessage
            WHERE task.id = :taskId AND task.status = 'processing'
              AND task.attemptCount = :expectedAttempt
            """)
    int finishProcessing(@Param("taskId") Long taskId,
                         @Param("expectedAttempt") int expectedAttempt,
                         @Param("resultJson") String resultJson,
                         @Param("fieldConfidence") String fieldConfidence,
                         @Param("evidence") String evidence,
                         @Param("modelRole") String modelRole,
                         @Param("model") String model,
                         @Param("status") String status,
                         @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task
            SET task.status = :status,
                task.errorMessage = :errorMessage
            WHERE task.id = :taskId AND task.status = 'processing'
              AND task.attemptCount = :expectedAttempt
            """)
    int finishProcessingError(@Param("taskId") Long taskId,
                              @Param("expectedAttempt") int expectedAttempt,
                              @Param("status") String status,
                              @Param("errorMessage") String errorMessage);
    boolean existsByTargetTypeAndTargetIdAndStatusIn(String targetType, Long targetId, List<String> statuses);

    @Query("SELECT task.songId FROM AiAnalysisTask task WHERE task.songId IN :songIds AND task.status IN :statuses")
    List<Long> findSongIdsBySongIdInAndStatusIn(@Param("songIds") Collection<Long> songIds,
                                                @Param("statuses") List<String> statuses);

    boolean existsByBatchId(String batchId);

    @Query("""
            SELECT task.status AS status, COUNT(task) AS taskCount
            FROM AiAnalysisTask task
            WHERE task.batchId = :batchId
            GROUP BY task.status
            """)
    List<BatchStatusCountProjection> countStatusesByBatchId(@Param("batchId") String batchId);

    @Query("""
            SELECT task.id FROM AiAnalysisTask task
            WHERE task.batchId = :batchId AND task.status = :status
            ORDER BY task.createdAt ASC, task.id ASC
            """)
    List<Long> findIdsByBatchIdAndStatus(@Param("batchId") String batchId, @Param("status") String status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AiAnalysisTask task
            SET task.status = 'pending'
            WHERE task.batchId = :batchId AND task.status = :fromStatus
            """)
    int resetBatchStatusToPending(@Param("batchId") String batchId, @Param("fromStatus") String fromStatus);
}
