package com.homektv.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.Song;
import com.homektv.domain.MediaImportRecord;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.homektv.web.ApiException;

/**
 * AI 分析任务工作器，异步执行歌曲 AI 分类分析。
 *
 * AI analysis task worker that asynchronously performs AI-based song classification.
 */
@Service
public class AiAnalysisWorker {
    private final AiAnalysisTaskRepository taskRepository;
    private final SongRepository songRepository;
    private final OpenAiCompatibleClient aiClient;
    private final ObjectMapper objectMapper;
    private final AiConfigService configService;
    private final AiAutoApplyPolicy autoApplyPolicy;
    private final AiClassificationApplier classificationApplier;
    private final AiConcurrencyLimiter concurrencyLimiter;
    private final MediaImportRecordRepository importRecordRepository;
    private final LocalClassificationService localClassificationService;

    public AiAnalysisWorker(AiAnalysisTaskRepository taskRepository, SongRepository songRepository,
                            OpenAiCompatibleClient aiClient, ObjectMapper objectMapper, AiConfigService configService,
                            AiAutoApplyPolicy autoApplyPolicy, AiClassificationApplier classificationApplier,
                            AiConcurrencyLimiter concurrencyLimiter, MediaImportRecordRepository importRecordRepository,
                            LocalClassificationService localClassificationService) {
        this.taskRepository = taskRepository;
        this.songRepository = songRepository;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.autoApplyPolicy = autoApplyPolicy;
        this.classificationApplier = classificationApplier;
        this.concurrencyLimiter = concurrencyLimiter;
        this.importRecordRepository = importRecordRepository;
        this.localClassificationService = localClassificationService;
    }

    /**
     * 异步执行 AI 分析任务：加载歌曲 → 调用 AI 分类 → 根据策略自动应用或标记为待审核。
     *
     * Asynchronously executes the AI analysis task: loads the song → calls AI classification →
     * auto-applies or marks for review based on policy.
     *
     * @param taskId AI 分析任务 ID / AI analysis task ID
     */
    @Async("aiBulkExecutor")
    public void analyze(Long taskId) {
        if (taskId == null || taskRepository.claimPending(taskId) != 1) return;
        AiAnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || isPaused(taskId)) return;
        try {
            boolean importTarget = "IMPORT_RECORD".equals(task.getTargetType());
            Song song = importTarget ? null : songRepository.findById(task.getSongId())
                    .orElseThrow(() -> new IllegalStateException("歌曲不存在: " + task.getSongId()));
            MediaImportRecord importRecord = importTarget ? importRecordRepository.findById(task.getTargetId())
                    .orElseThrow(() -> new IllegalStateException("导入记录不存在: " + task.getTargetId())) : null;
            AiConfigService.ResolvedConfig config = configService.resolve();
            AiSongClassification result;
            String fallbackReason = null;
            if (!configService.isConfigured() || "LOCAL".equals(task.getModelRole())) {
                result = importTarget ? localClassificationService.fromImport(importRecord)
                        : localClassificationService.fromSong(song);
                fallbackReason = "AI 未配置，已降级为本地解析结果，等待人工审核";
            } else {
                try {
                    try (AiConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquire(task.getModelRole())) {
                        result = importTarget ? aiClient.classifyImport(importRecord, task.getModelRole())
                                : aiClient.classify(song, task.getModelRole());
                    }
                } catch (RuntimeException providerFailure) {
                    result = importTarget ? localClassificationService.fromImport(importRecord)
                            : localClassificationService.fromSong(song);
                    fallbackReason = "AI 服务不可用，已保留并使用本地解析结果：" + safeMessage(providerFailure);
                }
            }
            if (fallbackReason != null) {
                task.setModelRole("LOCAL");
                task.setModel("LOCAL");
            }
            if (isPaused(taskId)) return;
            if (fallbackReason == null && config.reasoningModel() != null && !config.reasoningModel().isBlank()
                    && (result.titleConfidence() < config.identityThreshold()
                    || result.artistConfidence() < config.identityThreshold()
                    || result.languageConfidence() < config.classificationThreshold()
                    || result.vocalFormConfidence() < config.classificationThreshold())) {
                task.setModelRole("REASONING");
                task.setModel(config.modelFor("REASONING"));
                try {
                    try (AiConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquire("REASONING")) {
                        result = importTarget ? aiClient.classifyImport(importRecord, "REASONING")
                                : aiClient.classify(song, "REASONING");
                    }
                } catch (RuntimeException providerFailure) {
                    result = importTarget ? localClassificationService.fromImport(importRecord)
                            : localClassificationService.fromSong(song);
                    fallbackReason = "AI 增强模型不可用，已保留并使用本地解析结果：" + safeMessage(providerFailure);
                    task.setModelRole("LOCAL");
                    task.setModel("LOCAL");
                }
                if (isPaused(taskId)) return;
            }
            task.setResultJson(objectMapper.writeValueAsString(result));
            task.setFieldConfidence(objectMapper.writeValueAsString(java.util.Map.of(
                    "title", result.titleConfidence(), "artist", result.artistConfidence(),
                    "language", result.languageConfidence(), "vocalForm", result.vocalFormConfidence())));
            task.setEvidence(objectMapper.writeValueAsString(result.evidence()));
            if (fallbackReason != null) {
                task.setStatus("review");
                task.setErrorMessage(fallbackReason);
            } else if (importTarget && applyImportRecord(importRecord, result, config.identityThreshold())) {
                task.setStatus("auto_applied");
            } else if (!importTarget && autoApplyPolicy.shouldAutoApply(result) && classificationApplier.applyAuto(task.getSongId(), result)) {
                task.setStatus("auto_applied");
            } else {
                task.setStatus("review");
            }
        } catch (Exception e) {
            String terminalStatus = e instanceof ApiException api && "AI_IDENTITY_CONFLICT".equals(api.getCode())
                    ? "review" : "failed";
            taskRepository.finishProcessingError(taskId, terminalStatus, safeMessage(e));
            return;
        }
        taskRepository.finishProcessing(taskId,
                task.getResultJson(), task.getFieldConfidence(), task.getEvidence(),
                task.getModelRole(), task.getModel(), task.getStatus(), task.getErrorMessage());
    }

    private boolean isPaused(Long taskId) {
        return taskRepository.findById(taskId).map(task -> "paused".equals(task.getStatus())).orElse(false);
    }

    private boolean applyImportRecord(MediaImportRecord record, AiSongClassification result, double threshold) {
        if (result.title() == null || result.title().isBlank() || result.artist() == null || result.artist().isBlank()
                || result.titleConfidence() < threshold || result.artistConfidence() < threshold) return false;
        record.setParsedTitle(result.title().trim());
        record.setParsedArtist(result.artist().trim());
        record.setReason("AI 已优化文件身份：" + (result.reason() == null ? "" : result.reason()));
        importRecordRepository.save(record);
        return true;
    }

    // 安全截取异常信息（脱敏 API Key、限制长度 1000 字符）
    // Safely extract exception message (mask API key, cap at 1000 chars)
    private String safeMessage(Exception exception) {
        Throwable value = exception instanceof JsonProcessingException ? exception : exception.getCause();
        String message = value == null ? exception.getMessage() : value.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        String apiKey = configService.resolve().apiKey();
        if (!apiKey.isBlank()) message = message.replace(apiKey, "***");
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

}
