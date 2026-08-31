package com.homektv.library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ArtistAvatarAsyncConfig {
    @Bean("artistAvatarExecutor")
    public ThreadPoolTaskExecutor artistAvatarExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("artist-avatar-");
        executor.initialize();
        return executor;
    }
}
