package com.homektv.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class MediaStreamExecutorConfiguration {
    @Bean(name = "mediaStreamMvcTaskExecutor")
    public ThreadPoolTaskExecutor mediaStreamMvcTaskExecutor(
            @Value("${app.resource-budget.media-stream-limit:4}") int streamLimit) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(streamLimit);
        executor.setMaxPoolSize(streamLimit);
        executor.setQueueCapacity(streamLimit);
        executor.setThreadNamePrefix("media-stream-mvc-");
        executor.initialize();
        return executor;
    }
}
