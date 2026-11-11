package com.homektv.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Marks unfinished task rows from before this process startup as retryable failures. */
@Component
public class AiAnalysisTaskStartupRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisTaskStartupRecovery.class);

    private final AiAnalysisTaskRecoveryService recoveryService;

    public AiAnalysisTaskStartupRecovery(AiAnalysisTaskRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = recoveryService.recoverOrphanedTasks(OffsetDateTime.now(ZoneOffset.UTC));
        if (recovered > 0) {
            log.warn("服务器重启后将 {} 个未完成的 AI 任务标记为可重试失败", recovered);
        }
    }
}
