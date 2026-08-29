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
     * SONG_IN_QUEUE / TV_OFFLINE 属提示性，用 409/200 语义；其余统一 400 + code 区分。
     *
     * Handles ApiException and returns a unified {code, message} error response.
     * SONG_IN_QUEUE / TV_OFFLINE are informational, using 409/200 semantics;
     * others default to 400 + code for differentiation.
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
        // SONG_IN_QUEUE / TV_OFFLINE 属提示性，用 409/200 语义；这里统一 400 + code 区分
        HttpStatus status = "TV_OFFLINE".equals(e.getCode())
                ? HttpStatus.OK
                : "ADMIN_AUTH_RATE_LIMITED".equals(e.getCode())
                        ? HttpStatus.TOO_MANY_REQUESTS
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
