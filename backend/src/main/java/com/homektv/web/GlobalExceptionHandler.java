package com.homektv.web;

import com.homektv.ai.OpenAiCompatibleClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一错误响应 {code, message}（详设§11.2）。
 *
 * Unified error response {code, message} (see detailed design §11.2).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 ApiException 异常，返回统一的 {code, message} 错误响应。
     * SONG_IN_QUEUE / SONG_NOT_READY 属可重试的业务冲突，用 409；TV_OFFLINE
     * 保留 200 语义；其余统一 400 + code 区分。
     *
     * Handles ApiException and returns a unified {code, message} error response.
     * SONG_IN_QUEUE / SONG_NOT_READY are retryable business conflicts and use
     * 409; TV_OFFLINE keeps its 200 semantics; others default to 400.
     *
     * @param e 业务异常 / the business exception
     * @return 包含 code 和 message 的 ResponseEntity / a ResponseEntity containing code and message
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        if (e.getData() != null) body.put("data", e.getData());
        // Ordering/probe races are retryable business conflicts; TV_OFFLINE is
        // kept as a successful control response for existing clients.
        HttpStatus status = "TV_OFFLINE".equals(e.getCode())
                ? HttpStatus.OK
                : "ADMIN_AUTH_RATE_LIMITED".equals(e.getCode())
                        ? HttpStatus.TOO_MANY_REQUESTS
                        : "SONG_IN_QUEUE".equals(e.getCode()) || "SONG_NOT_READY".equals(e.getCode())
                                ? HttpStatus.CONFLICT
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 处理 IllegalArgumentException 异常，返回 INVALID_ARGUMENT 错误响应。
     *
     * Handles IllegalArgumentException and returns an INVALID_ARGUMENT error response.
     *
     * @param e 非法参数异常 / the illegal argument exception
     * @return 包含 INVALID_ARGUMENT 错误码的 ResponseEntity / a ResponseEntity with the INVALID_ARGUMENT error code
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_ARGUMENT",
                "message", e.getMessage() == null ? "参数错误" : e.getMessage()));
    }

    @ExceptionHandler(OpenAiCompatibleClient.AiProviderException.class)
    public ResponseEntity<Map<String, Object>> handleAi(OpenAiCompatibleClient.AiProviderException e) {
        return ResponseEntity.badRequest().body(Map.of("code", e.getCode(), "message", e.getMessage()));
    }
}
