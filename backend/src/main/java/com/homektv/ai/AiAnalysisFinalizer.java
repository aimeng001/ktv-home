package com.homektv.ai;

import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.MediaImportRecord;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits an AI task result and its optional target mutation as one transaction. */
@Service
public class AiAnalysisFinalizer {
    private final AiAnalysisTaskRepository taskRepository;
    private final MediaImportRecordRepository importRecordRepository;
    private final AiClassificationApplier classificationApplier;

    public AiAnalysisFinalizer(AiAnalysisTaskRepository taskRepository,
                               MediaImportRecordRepository importRecordRepository,
                               AiClassificationApplier classificationApplier) {
        this.taskRepository = taskRepository;
        this.importRecordRepository = importRecordRepository;
        this.classificationApplier = classificationApplier;
    }

    @Transactional
    public boolean finishProcessing(Long taskId, String resultJson, String fieldConfidence,
                                    String evidence, String modelRole, String model,
                                    AiSongClassification classification, boolean autoApplyCandidate,
                                    double identityThreshold, String reviewMessage) {
        AiAnalysisTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !"processing".equals(task.getStatus())) return false;

        boolean applied = autoApplyCandidate && applyTarget(task, classification, identityThreshold);
        task.setResultJson(resultJson);
        task.setFieldConfidence(fieldConfidence);
        task.setEvidence(evidence);
        task.setModelRole(modelRole);
        task.setModel(model);
        task.setStatus(applied ? "auto_applied" : "review");
        task.setErrorMessage(reviewMessage);
        return true;
    }

    private boolean applyTarget(AiAnalysisTask task, AiSongClassification result, double identityThreshold) {
        if ("IMPORT_RECORD".equals(task.getTargetType())) {
            if (!hasConfidentIdentity(result, identityThreshold)) return false;
            MediaImportRecord record = importRecordRepository.findById(task.getTargetId())
                    .orElseThrow(() -> new ApiException("IMPORT_RECORD_NOT_FOUND", "导入记录不存在"));
            record.setParsedTitle(result.title().trim());
            record.setParsedArtist(result.artist().trim());
            record.setReason("AI 已优化文件身份：" + (result.reason() == null ? "" : result.reason()));
            importRecordRepository.save(record);
            return true;
        }
        return classificationApplier.applyAuto(task.getSongId(), result);
    }

    private boolean hasConfidentIdentity(AiSongClassification result, double threshold) {
        return result.title() != null && !result.title().isBlank()
                && result.artist() != null && !result.artist().isBlank()
                && result.titleConfidence() >= threshold
                && result.artistConfidence() >= threshold;
    }
}
