package com.homektv.web;

import com.homektv.security.AdminAuthInterceptor;
import com.homektv.security.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public login/status/logout endpoints for the otherwise protected admin API. */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(HttpServletRequest httpRequest,
                               @RequestBody(required = false) LoginRequest request) {
        if (request == null) throw new ApiException("ADMIN_AUTH_INVALID", "管理员密码不能为空");
        String clientKey = httpRequest == null ? null : httpRequest.getRemoteAddr();
        String token = authService.login(request.password(), clientKey);
        return new LoginResponse(token, authService.sessionLifetimeSeconds());
    }

    @GetMapping("/status")
    public AuthStatus status(HttpServletRequest request) {
        return new AuthStatus(authService.isConfigured(),
                authService.isAuthenticated(AdminAuthInterceptor.token(request)));
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        authService.logout(AdminAuthInterceptor.token(request));
        return Map.of("ok", true);
    }

    public record LoginRequest(String password) { }
    public record LoginResponse(String token, long expiresInSeconds) { }
    public record AuthStatus(boolean configured, boolean authenticated) { }
}
