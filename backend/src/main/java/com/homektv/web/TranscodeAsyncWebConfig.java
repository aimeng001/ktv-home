package com.homektv.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class TranscodeAsyncWebConfig implements WebMvcConfigurer {
    @Autowired
    @Qualifier("mediaStreamMvcTaskExecutor")
    private AsyncTaskExecutor mediaStreamMvcTaskExecutor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mediaStreamMvcTaskExecutor);
        configurer.setDefaultTimeout(0L);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TranscodeAsyncLifecycleInterceptor())
                .addPathPatterns("/api/stream/**", "/api/playback/stream/**");
    }
}
