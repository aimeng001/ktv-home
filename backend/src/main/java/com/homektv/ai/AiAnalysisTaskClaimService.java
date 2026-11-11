package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalInt;

/** Atomically claims one pending task and returns the identity of that execution. */
@Service
public class AiAnalysisTaskClaimService {
    private final AiAnalysisTaskRepository taskRepository;

    public AiAnalysisTaskClaimService(AiAnalysisTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public OptionalInt claim(Long taskId) {
        AiAnalysisTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !"pending".equals(task.getStatus())) return OptionalInt.empty();

        task.setStatus("processing");
        task.setAttemptCount(Math.addExact(task.getAttemptCount(), 1));
        task.setErrorMessage(null);
        taskRepository.saveAndFlush(task);
        return OptionalInt.of(task.getAttemptCount());
    }
}
