package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void mapsAdminLoginRateLimitToTooManyRequests() {
        var response = new GlobalExceptionHandler().handleApi(
                new ApiException("ADMIN_AUTH_RATE_LIMITED", "请稍后再试"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
