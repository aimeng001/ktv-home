package com.homektv.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** Protects every management API while leaving public household APIs unchanged. */
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final AdminAuthService authService;

    public AdminAuthInterceptor(AdminAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!authService.isConfigured()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ADMIN_AUTH_NOT_CONFIGURED", "管理员密码尚未配置，请设置 KTV_ADMIN_PASSWORD");
            return false;
        }
        if (!authService.isAuthenticated(token(request))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "ADMIN_AUTH_REQUIRED", "请先登录管理后台");
            return false;
        }
        return true;
    }

    public static String token(HttpServletRequest request) {
        String token = request.getHeader("X-Admin-Token");
        if (token != null && !token.isBlank()) return token;
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
