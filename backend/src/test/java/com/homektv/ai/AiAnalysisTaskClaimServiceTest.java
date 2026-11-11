package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisTaskClaimServiceTest {
    @Test
    void claimsOnlyPendingTasksAndReturnsTheIncrementedExecutionAttempt() {
        AiAnalysisTaskRepository repository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisTask task = new AiAnalysisTask();
        task.setStatus("pending");
        task.setAttemptCount(4);
        task.setErrorMessage("上次失败");
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(task));
        AiAnalysisTaskClaimService service = new AiAnalysisTaskClaimService(repository);

        assertThat(service.claim(17L).orElseThrow()).isEqualTo(5);
        assertThat(task.getStatus()).isEqualTo("processing");
        assertThat(task.getAttemptCount()).isEqualTo(5);
        assertThat(task.getErrorMessage()).isNull();
        verify(repository).saveAndFlush(task);
    }

    @Test
    void doesNotClaimTasksThatAreMissingOrNoLongerPending() {
        AiAnalysisTaskRepository repository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisTask task = new AiAnalysisTask();
        task.setStatus("processing");
        task.setAttemptCount(2);
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(task));
        when(repository.findByIdForUpdate(18L)).thenReturn(Optional.empty());
        AiAnalysisTaskClaimService service = new AiAnalysisTaskClaimService(repository);

        assertThat(service.claim(17L)).isEmpty();
        assertThat(service.claim(18L)).isEmpty();
        assertThat(task.getAttemptCount()).isEqualTo(2);
    }
}
