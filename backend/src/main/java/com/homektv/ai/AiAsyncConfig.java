package com.homektv.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AiAsyncConfig {
    @Bean(name = "aiBulkExecutor")
    public ThreadPoolTaskExecutor aiBulkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ai-bulk-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiReasoningExecutor")
    public ThreadPoolTaskExecutor aiReasoningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-reasoning-");
        executor.initialize();
        return executor;
    }
}
