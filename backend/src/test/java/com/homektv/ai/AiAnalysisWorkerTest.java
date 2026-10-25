package com.homektv.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.Song;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

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
    void nullTaskIdAndDisappearedTaskStopBeforeLoadingTargets() {
        worker().analyze(null);
        verifyNoInteractions(taskRepository, songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, concurrencyLimiter, finalizer, importRecordRepository,
                localClassificationService);

        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.empty());
        worker().analyze(42L);

        verify(taskRepository).claimPending(42L);
        verify(taskRepository).findById(42L);
        verifyNoInteractions(songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, concurrencyLimiter, finalizer, importRecordRepository,
                localClassificationService);
    }

    @Test
    void alreadyPausedTaskReturnsBeforeLoadingItsTarget() {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "paused");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));

        worker().analyze(42L);

        verify(taskRepository, times(2)).findById(42L);
        verifyNoInteractions(songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, concurrencyLimiter, finalizer, importRecordRepository,
                localClassificationService);
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

    @Test
    void importRecordTaskUsesLocalFallbackWhenAiIsNotConfigured() throws Exception {
        AiAnalysisTask task = task(42L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        task.setTargetId(13L);
        MediaImportRecord record = mock(MediaImportRecord.class);
        AiSongClassification result = classification(0.8, 0.8, 0.8, 0.8);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, "");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.of(record));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(false);
        when(localClassificationService.fromImport(record)).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(localClassificationService).fromImport(record);
        verify(songRepository, never()).findById(any());
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "LOCAL", "LOCAL", result, false, 0.9,
                "AI 未配置，已降级为本地解析结果，等待人工审核");
    }

    @Test
    void configuredSongTaskCallsBulkProviderAndPolicy() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, "secret-key");
        AiConcurrencyLimiter.Permit permit = mock(AiConcurrencyLimiter.Permit.class);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(permit);
        when(aiClient.classify(song, "BULK")).thenReturn(result);
        when(autoApplyPolicy.shouldAutoApply(result)).thenReturn(true);
        when(finalizer.finishProcessing(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any())).thenReturn(true);
        stubSerialization(result);

        worker().analyze(42L);

        verify(aiClient).classify(song, "BULK");
        verify(concurrencyLimiter).acquire("BULK");
        verify(permit).close();
        verify(autoApplyPolicy).shouldAutoApply(result);
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "BULK", "bulk-model", result, true, 0.9, null);
        verify(localClassificationService, never()).fromSong(any());
    }

    @Test
    void configuredImportTaskUsesImportProviderAndIsAutoApplyCandidate() throws Exception {
        AiAnalysisTask task = task(42L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        task.setTargetId(13L);
        MediaImportRecord record = mock(MediaImportRecord.class);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, "secret-key");
        AiConcurrencyLimiter.Permit permit = mock(AiConcurrencyLimiter.Permit.class);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.of(record));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(permit);
        when(aiClient.classifyImport(record, "BULK")).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(aiClient).classifyImport(record, "BULK");
        verify(permit).close();
        verify(autoApplyPolicy, never()).shouldAutoApply(any());
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "BULK", "bulk-model", result, true, 0.9, null);
    }

    @Test
    void configuredImportTaskEscalatesToReasoningProvider() throws Exception {
        AiAnalysisTask task = task(42L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        task.setTargetId(13L);
        MediaImportRecord record = mock(MediaImportRecord.class);
        AiSongClassification bulk = classification(0.4, 0.95, 0.95, 0.95);
        AiSongClassification reasoning = classification(0.98, 0.98, 0.98, 0.98);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.of(record));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classifyImport(record, "BULK")).thenReturn(bulk);
        when(aiClient.classifyImport(record, "REASONING")).thenReturn(reasoning);
        stubSerialization(reasoning);

        worker().analyze(42L);

        verify(aiClient).classifyImport(record, "BULK");
        verify(aiClient).classifyImport(record, "REASONING");
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "REASONING", "reasoning-model", reasoning, true, 0.9, null);
    }

    @Test
    void primaryProviderFailureFallsBackLocallyAndMasksConfiguredKey() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification local = classification(0.6, 0.7, 0.8, 0.9);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, "secret-key");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenThrow(new IllegalStateException("服务拒绝 secret-key"));
        when(localClassificationService.fromSong(song)).thenReturn(local);
        stubSerialization(local);

        worker().analyze(42L);

        org.mockito.ArgumentCaptor<String> reviewReason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(finalizer).finishProcessing(eq(42L), eq("result-json"), eq("confidence-json"), eq("evidence-json"),
                eq("LOCAL"), eq("LOCAL"), eq(local), eq(false), eq(0.9), reviewReason.capture());
        assertTrue(reviewReason.getValue().contains("服务拒绝 ***"));
        assertFalse(reviewReason.getValue().contains("secret-key"));
    }

    @Test
    void lowConfidenceBulkResultEscalatesToReasoningModel() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification bulk = classification(0.4, 0.95, 0.95, 0.95);
        AiSongClassification reasoning = classification(0.98, 0.98, 0.98, 0.98);
        AiConfigService.ResolvedConfig config = config("reasoning-model", 0.9, 0.9, "secret-key");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(bulk);
        when(aiClient.classify(song, "REASONING")).thenReturn(reasoning);
        when(autoApplyPolicy.shouldAutoApply(reasoning)).thenReturn(true);
        stubSerialization(reasoning);

        worker().analyze(42L);

        verify(aiClient).classify(song, "BULK");
        verify(aiClient).classify(song, "REASONING");
        verify(concurrencyLimiter).acquire("REASONING");
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "REASONING", "reasoning-model", reasoning, true, 0.9, null);
    }

    @ParameterizedTest
    @CsvSource({
            "0.95,0.4,0.95,0.95",
            "0.95,0.95,0.4,0.95",
            "0.95,0.95,0.95,0.4"
    })
    void eachRemainingLowConfidenceFieldEscalates(double title, double artist, double language,
                                                   double vocalForm) throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification bulk = classification(title, artist, language, vocalForm);
        AiSongClassification reasoning = classification(0.98, 0.98, 0.98, 0.98);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(bulk);
        when(aiClient.classify(song, "REASONING")).thenReturn(reasoning);
        stubSerialization(reasoning);

        worker().analyze(42L);

        verify(aiClient).classify(song, "REASONING");
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "REASONING", "reasoning-model", reasoning, false, 0.9, null);
    }

    @Test
    void highConfidenceSkipsReasoningWhenReasoningModelIsMissing() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config(null, 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(aiClient, never()).classify(song, "REASONING");
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "BULK", "bulk-model", result, false, 0.9, null);
    }

    @Test
    void highConfidenceSkipsConfiguredReasoningModel() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(aiClient, never()).classify(song, "REASONING");
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "BULK", "bulk-model", result, false, 0.9, null);
    }

    @Test
    void configuredLocalRoleUsesLocalClassificationWithoutCallingProvider() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "LOCAL", "LOCAL", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.7, 0.7, 0.7, 0.7);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(localClassificationService.fromSong(song)).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(localClassificationService).fromSong(song);
        verifyNoInteractions(aiClient, concurrencyLimiter);
        verify(finalizer).finishProcessing(42L, "result-json", "confidence-json", "evidence-json",
                "LOCAL", "LOCAL", result, false, 0.9,
                "AI 未配置，已降级为本地解析结果，等待人工审核");
    }

    @Test
    void importProviderFailureUsesLocalImportFallbackAndSafeMessage() throws Exception {
        AiAnalysisTask task = task(42L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        task.setTargetId(13L);
        MediaImportRecord record = mock(MediaImportRecord.class);
        AiSongClassification result = classification(0.8, 0.8, 0.8, 0.8);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, "");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.of(record));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classifyImport(record, "BULK")).thenThrow(new IllegalStateException());
        when(localClassificationService.fromImport(record)).thenReturn(result);
        stubSerialization(result);

        worker().analyze(42L);

        verify(localClassificationService).fromImport(record);
        verify(finalizer).finishProcessing(eq(42L), eq("result-json"), eq("confidence-json"), eq("evidence-json"),
                eq("LOCAL"), eq("LOCAL"), eq(result), eq(false), eq(0.9),
                org.mockito.ArgumentMatchers.contains("IllegalStateException"));
    }

    @Test
    void reasoningProviderFailureUsesLocalSongFallback() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification bulk = classification(0.4, 0.95, 0.95, 0.95);
        AiSongClassification local = classification(0.6, 0.7, 0.8, 0.9);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(bulk);
        when(aiClient.classify(song, "REASONING")).thenThrow(new IllegalStateException("reasoning unavailable"));
        when(localClassificationService.fromSong(song)).thenReturn(local);
        stubSerialization(local);

        worker().analyze(42L);

        verify(localClassificationService).fromSong(song);
        verify(finalizer).finishProcessing(eq(42L), eq("result-json"), eq("confidence-json"), eq("evidence-json"),
                eq("LOCAL"), eq("LOCAL"), eq(local), eq(false), eq(0.9),
                org.mockito.ArgumentMatchers.contains("AI 增强模型不可用"));
    }

    @Test
    void reasoningImportProviderFailureUsesLocalImportFallback() throws Exception {
        AiAnalysisTask task = task(42L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        task.setTargetId(13L);
        MediaImportRecord record = mock(MediaImportRecord.class);
        AiSongClassification bulk = classification(0.4, 0.95, 0.95, 0.95);
        AiSongClassification local = classification(0.6, 0.7, 0.8, 0.9);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.of(record));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classifyImport(record, "BULK")).thenReturn(bulk);
        when(aiClient.classifyImport(record, "REASONING"))
                .thenThrow(new IllegalStateException("reasoning unavailable"));
        when(localClassificationService.fromImport(record)).thenReturn(local);
        stubSerialization(local);

        worker().analyze(42L);

        verify(aiClient).classifyImport(record, "REASONING");
        verify(localClassificationService).fromImport(record);
        verify(finalizer).finishProcessing(eq(42L), eq("result-json"), eq("confidence-json"), eq("evidence-json"),
                eq("LOCAL"), eq("LOCAL"), eq(local), eq(false), eq(0.9),
                org.mockito.ArgumentMatchers.contains("AI 增强模型不可用"));
    }

    @Test
    void pauseObservedAfterBulkAnalysisStopsBeforeSerializationAndFinalization() {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        AiAnalysisTask paused = task(42L, "SONG", "BULK", "bulk-model", "paused");
        Song song = song(7L);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task), Optional.of(task), Optional.of(paused));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(result);

        worker().analyze(42L);

        verifyNoInteractions(objectMapper, finalizer);
    }

    @Test
    void pauseObservedAfterReasoningProviderStopsBeforeSerialization() {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        AiAnalysisTask paused = task(42L, "SONG", "REASONING", "reasoning-model", "paused");
        Song song = song(7L);
        AiSongClassification bulk = classification(0.4, 0.95, 0.95, 0.95);
        AiSongClassification reasoning = classification(0.98, 0.98, 0.98, 0.98);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(
                Optional.of(task), Optional.of(task), Optional.of(task), Optional.of(paused));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("reasoning-model", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(true);
        when(concurrencyLimiter.acquire("BULK")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(concurrencyLimiter.acquire("REASONING")).thenReturn(mock(AiConcurrencyLimiter.Permit.class));
        when(aiClient.classify(song, "BULK")).thenReturn(bulk);
        when(aiClient.classify(song, "REASONING")).thenReturn(reasoning);

        worker().analyze(42L);

        verifyNoInteractions(objectMapper, finalizer);
    }

    @Test
    void missingSongAndImportRecordAreRecordedAsFailedTasks() {
        AiAnalysisTask songTask = task(42L, "SONG", "BULK", "bulk-model", "processing");
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(songTask));
        when(songRepository.findById(7L)).thenReturn(Optional.empty());
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));

        worker().analyze(42L);

        verify(taskRepository).finishProcessingError(42L, "failed", "歌曲不存在: 7");

        org.mockito.Mockito.reset(taskRepository, songRepository, importRecordRepository, configService);
        AiAnalysisTask importTask = task(43L, "IMPORT_RECORD", "BULK", "bulk-model", "processing");
        importTask.setTargetId(13L);
        when(taskRepository.claimPending(43L)).thenReturn(1);
        when(taskRepository.findById(43L)).thenReturn(Optional.of(importTask));
        when(importRecordRepository.findById(13L)).thenReturn(Optional.empty());
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));

        worker().analyze(43L);

        verify(taskRepository).finishProcessingError(43L, "failed", "导入记录不存在: 13");
    }

    @Test
    void identityConflictDuringFinalizationUsesReviewStatus() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.95, 0.95, 0.95, 0.95);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(false);
        when(localClassificationService.fromSong(song)).thenReturn(result);
        stubSerialization(result);
        doThrow(new ApiException("AI_IDENTITY_CONFLICT", "身份冲突待审核"))
                .when(finalizer).finishProcessing(any(), any(), any(), any(), any(), any(), any(),
                        anyBoolean(), anyDouble(), any());

        worker().analyze(42L);

        verify(taskRepository).finishProcessingError(42L, "review", "身份冲突待审核");
    }

    @Test
    void nonIdentityBusinessErrorUsesFailedStatus() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.7, 0.7, 0.7, 0.7);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));
        when(configService.isConfigured()).thenReturn(false);
        when(localClassificationService.fromSong(song)).thenReturn(result);
        when(objectMapper.writeValueAsString(result))
                .thenThrow(new ApiException("AI_PROVIDER_ERROR", "提供商返回错误"));

        worker().analyze(42L);

        verify(taskRepository).finishProcessingError(42L, "failed", "提供商返回错误");
    }

    @Test
    void safeMessageHandlesJsonProcessingNullAndWhitespaceMessages() {
        when(configService.resolve()).thenReturn(config("", 0.9, 0.9, ""));
        JsonProcessingException jsonFailure = new JsonProcessingException("JSON 编码失败") { };

        String jsonMessage = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                worker(), "safeMessage", jsonFailure);
        String nullMessage = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                worker(), "safeMessage", new IllegalStateException());
        String blankMessage = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                worker(), "safeMessage", new IllegalStateException("  "));

        assertTrue("JSON 编码失败".equals(jsonMessage));
        assertTrue("IllegalStateException".equals(nullMessage));
        assertTrue("IllegalStateException".equals(blankMessage));
    }

    @Test
    void serializationFailureStoresCauseWithKeyMaskedAndMessageCapped() throws Exception {
        AiAnalysisTask task = task(42L, "SONG", "BULK", "bulk-model", "processing");
        Song song = song(7L);
        AiSongClassification result = classification(0.6, 0.7, 0.8, 0.9);
        String secret = "secret-key";
        String longCause = secret + "x".repeat(1200);
        AiConfigService.ResolvedConfig config = config("", 0.9, 0.9, secret);
        when(taskRepository.claimPending(42L)).thenReturn(1);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(songRepository.findById(7L)).thenReturn(Optional.of(song));
        when(configService.resolve()).thenReturn(config);
        when(configService.isConfigured()).thenReturn(false);
        when(localClassificationService.fromSong(song)).thenReturn(result);
        when(objectMapper.writeValueAsString(result))
                .thenThrow(new IllegalStateException("wrapper", new IllegalArgumentException(longCause)));

        worker().analyze(42L);

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(taskRepository).finishProcessingError(eq(42L), eq("failed"), message.capture());
        assertTrue(message.getValue().length() == 1000);
        assertFalse(message.getValue().contains(secret));
        assertTrue(message.getValue().contains("***"));
    }

    private AiAnalysisTask task(Long taskId, String targetType, String role, String model, String status) {
        AiAnalysisTask task = new AiAnalysisTask();
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", taskId);
        task.setSongId("SONG".equals(targetType) ? 7L : null);
        task.setTargetType(targetType);
        task.setTargetId("SONG".equals(targetType) ? 7L : null);
        task.setModelRole(role);
        task.setModel(model);
        task.setStatus(status);
        return task;
    }

    private Song song(Long id) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("歌曲");
        song.setArtist("歌手");
        return song;
    }

    private AiSongClassification classification(double title, double artist, double language, double vocalForm) {
        return new AiSongClassification("歌曲", "歌手", "国语", "90年代", List.of(), List.of(),
                "全年龄", "独唱", List.of(), "测试分类", 0.9,
                title, artist, language, vocalForm, "未知", Map.of());
    }

    private AiConfigService.ResolvedConfig config(String reasoningModel, double identityThreshold,
                                                  double classificationThreshold, String apiKey) {
        return new AiConfigService.ResolvedConfig(true, "http://127.0.0.1:1234", "bulk-model", reasoningModel,
                30, identityThreshold, classificationThreshold, AiConfigService.JsonMode.AUTO,
                2, 1, apiKey);
    }

    private void stubSerialization(AiSongClassification result) throws Exception {
        when(objectMapper.writeValueAsString(result)).thenReturn("result-json");
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.anyMap())).thenReturn("confidence-json");
        when(objectMapper.writeValueAsString(result.evidence())).thenReturn("evidence-json");
    }

    private AiAnalysisWorker worker() {
        return new AiAnalysisWorker(taskRepository, finalizer, songRepository, aiClient, objectMapper, configService,
                autoApplyPolicy, concurrencyLimiter, importRecordRepository,
                localClassificationService);
    }
}
