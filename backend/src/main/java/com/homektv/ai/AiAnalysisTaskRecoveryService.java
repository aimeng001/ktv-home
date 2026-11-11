package com.homektv.ai;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** Recovers unfinished AI tasks left by a previous server process. */
@Service
public class AiAnalysisTaskRecoveryService {
    private final AiAnalysisTaskRecoveryBatch recoveryBatch;

    public AiAnalysisTaskRecoveryService(AiAnalysisTaskRecoveryBatch recoveryBatch) {
        this.recoveryBatch = recoveryBatch;
    }

    public int recoverOrphanedTasks(OffsetDateTime startupCutoff) {
        int recovered = 0;
        int batchSize;
        do {
            batchSize = recoveryBatch.failNextBatch(startupCutoff);
            recovered += batchSize;
        } while (batchSize > 0);
        return recovered;
    }
}
