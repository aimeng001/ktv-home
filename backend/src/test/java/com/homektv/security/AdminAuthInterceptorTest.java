package com.homektv.security;

import com.homektv.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthInterceptorTest {
    private AppProperties properties;
    private AdminAuthService service;
    private AdminAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        service = new AdminAuthService(properties);
        interceptor = new AdminAuthInterceptor(service);
    }

    @Test
    void rejectsAdminRequestWhenPasswordIsNotConfigured() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request(null), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(response.getContentAsString()).contains("ADMIN_AUTH_NOT_CONFIGURED");
    }

    @Test
    void rejectsMissingOrInvalidToken() throws Exception {
        properties.setAdminPassword("correct-password");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("invalid-token"), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("ADMIN_AUTH_REQUIRED");
    }

    @Test
    void acceptsValidToken() throws Exception {
        properties.setAdminPassword("correct-password");
        String token = service.login("correct-password");

        boolean allowed = interceptor.preHandle(request(token), new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
    }

    private HttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) request.addHeader("X-Admin-Token", token);
        return request;
    }
}
