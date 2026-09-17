package com.homektv.repo;

import com.homektv.domain.AiAnalysisTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * AI分析任务数据访问层，对应 ai_analysis_task 表。
 *
 * AI analysis task repository, mapping to the ai_analysis_task table.
 */
public interface AiAnalysisTaskRepository extends JpaRepository<AiAnalysisTask, Long> {
    List<AiAnalysisTask> findTop100ByOrderByCreatedAtDesc();
    List<AiAnalysisTask> findByBatchIdOrderByCreatedAtAsc(String batchId);
    boolean existsBySongIdAndStatusIn(Long songId, List<String> statuses);
    Optional<AiAnalysisTask> findFirstBySongIdAndStatusInOrderByCreatedAtDesc(Long songId, List<String> statuses);
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
            """)
    int finishProcessing(@Param("taskId") Long taskId,
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
            """)
    int finishProcessingError(@Param("taskId") Long taskId,
                              @Param("status") String status,
                              @Param("errorMessage") String errorMessage);
    boolean existsByTargetTypeAndTargetIdAndStatusIn(String targetType, Long targetId, List<String> statuses);
}
