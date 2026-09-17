package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAnalysisWorkerTest {

    @Mock
    private AiAnalysisTaskRepository taskRepository;
    @Mock
    private SongRepository songRepository;
    @Mock
    private OpenAiCompatibleClient aiClient;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AiConfigService configService;
    @Mock
    private AiAutoApplyPolicy autoApplyPolicy;
    @Mock
    private AiClassificationApplier classificationApplier;
    @Mock
    private AiConcurrencyLimiter concurrencyLimiter;
    @Mock
    private MediaImportRecordRepository importRecordRepository;
    @Mock
    private LocalClassificationService localClassificationService;

    @Test
    void workerThatLosesAtomicClaimDoesNotLoadTaskOrCallProvider() {
        when(taskRepository.claimPending(42L)).thenReturn(0);

        worker().analyze(42L);

        verify(taskRepository).claimPending(42L);
        verify(taskRepository, never()).findById(42L);
        verifyNoInteractions(songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, classificationApplier, concurrencyLimiter,
                importRecordRepository, localClassificationService);
    }

    @Test
    void claimedLocalTaskPublishesTerminalStateThroughRepositoryUpdate() throws Exception {
        AiAnalysisTask task = new AiAnalysisTask();
        task.setSongId(7L);
        task.setTargetType("SONG");
        task.setTargetId(7L);
        task.setStatus("processing");
        task.setModel("LOCAL");
        SongRepository songRepository = this.songRepository;
        com.homektv.domain.Song song = new com.homektv.domain.Song();
        song.setId(7L);
        AiSongClassification result = new AiSongClassification(
                "歌曲", "歌手", "国语", "90年代", java.util.List.of(), java.util.List.of(),
                "全年龄", "独唱", java.util.List.of(), "本地解析", 0.5,
                0.5, 0.5, 0.5, 0.5, "未知", java.util.Map.of());
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.isConfigured()).thenReturn(false);
        when(localClassificationService.fromSong(song)).thenReturn(result);
        when(objectMapper.writeValueAsString(result)).thenReturn("result-json");
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(java.util.Map.class)))
                .thenReturn("confidence-json");
        when(objectMapper.writeValueAsString(result.evidence())).thenReturn("evidence-json");

        worker().analyze(42L);

        verify(taskRepository).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "LOCAL", "LOCAL", "review", "AI 未配置，已降级为本地解析结果，等待人工审核");
        verify(aiClient, never()).classify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(classificationApplier, never()).applyAuto(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private AiAnalysisWorker worker() {
        return new AiAnalysisWorker(taskRepository, songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, classificationApplier, concurrencyLimiter, importRecordRepository,
                localClassificationService);
    }
}