package com.homektv.library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ArtistAvatarAsyncConfig {
    @Bean("artistAvatarExecutor")
    public ThreadPoolTaskExecutor artistAvatarExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(8);
        executor.setDaemon(true);
        executor.setThreadNamePrefix("artist-avatar-");
        executor.initialize();
        return executor;
    }
}
