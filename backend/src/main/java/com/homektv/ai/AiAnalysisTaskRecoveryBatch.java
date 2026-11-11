package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Applies one bounded, atomic recovery batch. */
@Service
public class AiAnalysisTaskRecoveryBatch {
    static final String RESTART_MESSAGE = "服务重启时 AI 分析任务未完成，请重试";
    private static final List<String> ORPHANED_STATUSES = List.of("pending", "processing");

    private final AiAnalysisTaskRepository taskRepository;

    public AiAnalysisTaskRecoveryBatch(AiAnalysisTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public int failNextBatch(OffsetDateTime startupCutoff) {
        List<AiAnalysisTask> tasks = taskRepository
                .findTop500ByStatusInAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
                        ORPHANED_STATUSES, startupCutoff);
        if (tasks.isEmpty()) return 0;

        for (AiAnalysisTask task : tasks) {
            task.setStatus("failed");
            task.setErrorMessage(RESTART_MESSAGE);
        }
        taskRepository.saveAllAndFlush(tasks);
        return tasks.size();
    }
}
