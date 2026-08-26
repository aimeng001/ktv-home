package com.homektv.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the narrow admin-only authentication boundary. */
@Configuration
public class AdminAuthWebConfig implements WebMvcConfigurer {
    private final ObjectProvider<AdminAuthService> authService;

    public AdminAuthWebConfig(ObjectProvider<AdminAuthService> authService) {
        this.authService = authService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        AdminAuthService service = authService.getIfAvailable();
        if (service == null) return;
        registry.addInterceptor(new AdminAuthInterceptor(service))
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/**");
    }
}
