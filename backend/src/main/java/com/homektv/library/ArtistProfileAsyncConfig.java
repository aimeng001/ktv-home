package com.homektv.library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ArtistProfileAsyncConfig {
    @Bean("artistProfileExecutor")
    public ThreadPoolTaskExecutor artistProfileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("artist-profile-");
        executor.initialize();
        return executor;
    }
}
