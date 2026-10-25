package com.homektv.ai;

import com.homektv.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerContractTest {
    @Test
    void preservesTheAiProviderErrorCodeAndMessageInTheUnifiedHttpResponse() {
        var failure = new OpenAiCompatibleClient.AiProviderException(
                "AI_JSON_INVALID", "AI 服务返回内容无效", null);

        var response = new GlobalExceptionHandler().handleAi(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "AI_JSON_INVALID")
                .containsEntry("message", "AI 服务返回内容无效");
    }
}
