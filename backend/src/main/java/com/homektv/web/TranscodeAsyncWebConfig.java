package com.homektv.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

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
                .addPathPatterns("/api/stream/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite hashed bundles under /m/assets/ are content-addressed and safe to cache immutably.
        registry.addResourceHandler("/m/assets/**")
                .addResourceLocations("classpath:/static/m/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        // Always revalidate the SPA entry HTML so new releases take effect immediately.
        registry.addResourceHandler("/m/index.html")
                .addResourceLocations("classpath:/static/m/index.html")
                .setCacheControl(CacheControl.noCache());
    }
}
