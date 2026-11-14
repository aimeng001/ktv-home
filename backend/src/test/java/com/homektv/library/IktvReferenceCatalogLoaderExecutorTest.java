package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IktvReferenceCatalogLoaderExecutorTest {

    @Test
    void executorHasSufficientQueueCapacityAndRejectionHandler() {
        IktvReferenceCatalogAsyncConfig config = new IktvReferenceCatalogAsyncConfig();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.iktvReferenceCatalogExecutor();

        assertTrue(executor.getQueueCapacity() >= 100, "Queue capacity must be at least 100 to prevent saturation");
        assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy
                || executor.getThreadPoolExecutor().getRejectedExecutionHandler() instanceof ThreadPoolExecutor.DiscardOldestPolicy,
                "Must configure graceful fallback rejection policy");
    }
}
