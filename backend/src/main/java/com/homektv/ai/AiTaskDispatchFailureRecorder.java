package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskDispatchFailureRecorder {
    private static final String QUEUE_FULL_MESSAGE = "AI_QUEUE_FULL: AI 分析队列已满，请稍后重试";

    private final AiAnalysisTaskRepository taskRepository;

    public AiTaskDispatchFailureRecorder(AiAnalysisTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markQueueRejected(Long taskId) {
        if (taskId == null) return;
        taskRepository.findById(taskId).ifPresent(task -> {
            if (!"pending".equals(task.getStatus())) return;
            task.setStatus("failed");
            task.setErrorMessage(QUEUE_FULL_MESSAGE);
            taskRepository.save(task);
        });
    }
}
