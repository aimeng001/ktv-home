package com.homektv.web;

import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.ProviderHttpException;
import com.homektv.musicsource.ProviderRateLimitedException;
import com.homektv.musicsource.ProviderRateStateUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void mapsAdminLoginRateLimitToTooManyRequests() {
        var response = new GlobalExceptionHandler().handleApi(
                new ApiException("ADMIN_AUTH_RATE_LIMITED", "请稍后再试"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void mapsRetryableSongAvailabilityToConflict() {
        var response = new GlobalExceptionHandler().handleApi(
                new ApiException("SONG_NOT_READY", "媒体探测未完成"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void preservesSuccessfulOfflineSemanticsAndOptionalBusinessData() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var offline = handler.handleApi(new ApiException("TV_OFFLINE", "电视离线"));
        var queued = handler.handleApi(new ApiException("SONG_IN_QUEUE", "已在队列", Map.of("queueId", 7L)));
        var invalid = handler.handleApi(new ApiException("INVALID_ACTION", "操作无效"));

        assertThat(offline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(offline.getBody()).containsEntry("code", "TV_OFFLINE");
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(queued.getBody()).containsEntry("data", Map.of("queueId", 7L));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).doesNotContainKey("data");
    }

    @Test
    void usesTheIllegalArgumentFallbackOnlyWhenTheExceptionHasNoMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var withMessage = handler.handleIllegal(new IllegalArgumentException("参数越界"));
        var withoutMessage = handler.handleIllegal(new IllegalArgumentException());

        assertThat(withMessage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withMessage.getBody()).containsEntry("code", "INVALID_ARGUMENT")
                .containsEntry("message", "参数越界");
        assertThat(withoutMessage.getBody()).containsEntry("message", "参数错误");
    }

    @Test
    void mapsProviderRateLimitAndUnavailableStateToRetryableHttpStatuses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Instant retryAt = Instant.parse("2026-10-22T10:15:30Z");

        var limited = handler.handleProviderRateLimited(
                new ProviderRateLimitedException(MusicProvider.QQ, retryAt));
        var unavailable = handler.handleProviderRateStateUnavailable(
                new ProviderRateStateUnavailableException(MusicProvider.NETEASE, new IllegalStateException()));

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).containsEntry("code", "MUSIC_PROVIDER_RATE_LIMITED")
                .containsEntry("retryAt", retryAt.toString());
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody()).containsEntry("code", "MUSIC_PROVIDER_RATE_STATE_UNAVAILABLE");
    }

    @Test
    void mapsProvider429ToRateLimitedAndOtherStatusesToBadGateway() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var rateLimited = handler.handleProviderHttp(
                new ProviderHttpException(MusicProvider.QQ, 429, null, "上游限流"));
        var badGateway = handler.handleProviderHttp(
                new ProviderHttpException(MusicProvider.QQ, 503, null, "上游不可用"));

        assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimited.getBody()).containsEntry("status", 429)
                .containsEntry("code", "MUSIC_PROVIDER_HTTP_ERROR");
        assertThat(badGateway.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(badGateway.getBody()).containsEntry("status", 503);
    }
}
