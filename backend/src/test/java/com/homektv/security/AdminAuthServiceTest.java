package com.homektv.security;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthServiceTest {
    private AppProperties properties;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        service = new AdminAuthService(properties);
    }

    @Test
    void missingPasswordFailsClosedWithoutDisablingPublicService() {
        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.login("anything"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_NOT_CONFIGURED");
    }

    @Test
    void wrongPasswordDoesNotCreateUsableSession() {
        properties.setAdminPassword("correct-password");

        assertThatThrownBy(() -> service.login("wrong-password"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("ADMIN_AUTH_INVALID");
    }

    @Test
    void correctPasswordCreatesRandomSessionThatCanBeLoggedOut() {
        properties.setAdminPassword("correct-password");

        String token = service.login("correct-password");

        assertThat(token).isNotBlank();
        assertThat(token).doesNotContain("correct-password");
        assertThat(service.isAuthenticated(token)).isTrue();
        service.logout(token);
        assertThat(service.isAuthenticated(token)).isFalse();
    }
}
