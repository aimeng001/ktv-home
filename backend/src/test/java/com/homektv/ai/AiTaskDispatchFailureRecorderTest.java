package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskDispatchFailureRecorderTest {
    @Test
    void marksARejectedPendingTaskAsRetryableFailure() {
        AiAnalysisTaskRepository repository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisTask task = new AiAnalysisTask();
        task.setStatus("pending");
        when(repository.findById(41L)).thenReturn(Optional.of(task));
        AiTaskDispatchFailureRecorder recorder = new AiTaskDispatchFailureRecorder(repository);

        recorder.markQueueRejected(41L);

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getErrorMessage()).startsWith("AI_QUEUE_FULL:");
        verify(repository).save(task);
    }

    @Test
    void doesNotOverwriteAConcurrentTaskStateChange() {
        AiAnalysisTaskRepository repository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisTask task = new AiAnalysisTask();
        task.setStatus("paused");
        when(repository.findById(41L)).thenReturn(Optional.of(task));
        AiTaskDispatchFailureRecorder recorder = new AiTaskDispatchFailureRecorder(repository);

        recorder.markQueueRejected(41L);

        assertThat(task.getStatus()).isEqualTo("paused");
        verify(repository, never()).save(task);
    }
}
