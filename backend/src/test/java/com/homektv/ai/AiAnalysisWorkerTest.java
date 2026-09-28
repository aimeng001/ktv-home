package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.Song;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAnalysisWorkerTest {

    @Mock
    private AiAnalysisTaskRepository taskRepository;
    @Mock
    private AiAnalysisFinalizer finalizer;
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
                autoApplyPolicy, concurrencyLimiter, finalizer,
                importRecordRepository, localClassificationService);
    }

    @Test
    void claimedLocalTaskPublishesTerminalStateThroughFinalizer() throws Exception {
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

        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "LOCAL", "LOCAL", result, false, 0.0,
                "AI 未配置，已降级为本地解析结果，等待人工审核");
        verify(aiClient, never()).classify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void workerDefersAutomaticMutationUntilFinalizerConfirmsProcessingOwnership() throws Exception {
        AiAnalysisTask task = new AiAnalysisTask();
        task.setSongId(7L);
        task.setTargetType("SONG");
        task.setTargetId(7L);
        task.setBatchId("batch-pause-race");
        task.setStatus("processing");
        task.setModelRole("BULK");
        task.setModel("bulk-model");

        Song song = new Song();
        song.setId(7L);
        AiSongClassification result = new AiSongClassification(
                "新歌名", "新歌手", "国语", "90年代", java.util.List.of(), java.util.List.of(),
                "全年龄", "独唱", java.util.List.of(), "测试分类", 0.99,
                0.99, 0.99, 0.99, 0.99, "未知", java.util.Map.of());
        AiConfigService.ResolvedConfig config = new AiConfigService.ResolvedConfig(
                true, "http://127.0.0.1:1234", "bulk-model", "", 30,
                0.90, 0.90, AiConfigService.JsonMode.AUTO, 1, 1, "test-key");
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch continueApply = new CountDownLatch(1);
        AtomicBoolean songMutationCommitted = new AtomicBoolean();

        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.isConfigured()).thenReturn(true);
        when(configService.resolve()).thenReturn(config);
        when(aiClient.classify(song, "BULK")).thenReturn(result);
        when(autoApplyPolicy.shouldAutoApply(result)).thenReturn(true);
        when(objectMapper.writeValueAsString(result)).thenReturn("result-json");
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.anyMap())).thenReturn("confidence-json");
        when(objectMapper.writeValueAsString(result.evidence())).thenReturn("evidence-json");
        doAnswer(invocation -> {
            applying.countDown();
            if (!continueApply.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release the finalization barrier");
            }
            if (!"processing".equals(task.getStatus())) return false;
            songMutationCommitted.set(true);
            return true;
        }).when(finalizer).finishProcessing(
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(result), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.isNull());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = executor.submit(() -> worker().analyze(42L));
            assertTrue(applying.await(5, TimeUnit.SECONDS), "worker should delegate finalization after its pause check");

            // The latch is entered only after analyze() has passed its last isPaused() check.
            task.setStatus("paused");
            continueApply.countDown();
            run.get(5, TimeUnit.SECONDS);

            assertFalse(songMutationCommitted.get(),
                    "the worker must leave target writes to the finalizer that checks task ownership");
        } finally {
            continueApply.countDown();
            executor.shutdownNow();
        }
    }

    private AiAnalysisWorker worker() {
        return new AiAnalysisWorker(taskRepository, finalizer, songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, concurrencyLimiter, importRecordRepository,
                localClassificationService);
    }
}
