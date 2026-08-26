package com.homektv.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsArbitraryModelAndFencedJson() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(read(exchange));
            respond(exchange, 200, completion("```json\n{\"ok\":true}\n```"));
        });
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY);

        JsonNode result = client.completeJson("BULK", "system", "user", 100);

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(bodies).singleElement().asString().contains("vendor/model:any-v2").doesNotContain("response_format");
    }

    @Test
    void autoModeFallsBackWhenResponseFormatIsUnsupported() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            String body = read(exchange);
            bodies.add(body);
            if (body.contains("response_format")) respond(exchange, 400, "{\"error\":{\"message\":\"unsupported\"}}");
            else respond(exchange, 200, completion("{\"ok\":true}"));
        });

        JsonNode result = client(AiConfigService.JsonMode.AUTO).completeJson("BULK", "system", "user", 100);

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("response_format");
        assertThat(bodies.get(1)).doesNotContain("response_format");
    }

    @Test
    void autoModeFallsBackWhenJsonResponseIsInvalid() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            String body = read(exchange);
            bodies.add(body);
            if (body.contains("response_format")) respond(exchange, 200, completion("analysis without json"));
            else respond(exchange, 200, completion("{'ok':true,}"));
        });

        JsonNode result = client(AiConfigService.JsonMode.AUTO).completeJson("BULK", "system", "user", 100);

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("response_format");
        assertThat(bodies.get(1)).doesNotContain("response_format");
    }

    @Test
    void acceptsSegmentedContentAndIgnoresThinkingText() throws Exception {
        start(exchange -> {
            read(exchange);
            String response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(
                    java.util.Map.of("message", java.util.Map.of("content", List.of(
                            java.util.Map.of("type", "text", "text", "<think>先分析一下</think>"),
                            java.util.Map.of("type", "text", "text", "结果如下：{\"ok\":true} 后续说明")))))));
            respond(exchange, 200, response);
        });

        JsonNode result = client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100);

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    void acceptsJsonFromReasoningContentWhenContentIsEmpty() throws Exception {
        start(exchange -> {
            read(exchange);
            String response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(
                    java.util.Map.of("message", java.util.Map.of("content", "", "reasoning_content", "{\"ok\":true}")))));
            respond(exchange, 200, response);
        });

        assertThat(client(AiConfigService.JsonMode.PROMPT_ONLY).completeJson("BULK", "system", "user", 100)
                .path("ok").asBoolean()).isTrue();
    }

    @Test
    void retriesRateLimitAndListsModels() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 200, "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"}]}");
                return;
            }
            read(exchange);
            if (completions.getAndIncrement() == 0) respond(exchange, 429, "{\"error\":{\"message\":\"rate limit\"}}");
            else respond(exchange, 200, completion("{\"ok\":true}"));
        });
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY);

        assertThat(client.completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        assertThat(client.listModels()).containsExactly("a-model", "z-model");
        assertThat(completions.get()).isEqualTo(2);
    }

    @Test
    void validatesDestinationBeforeSendingBearerToken() {
        AiConfigService configService = mock(AiConfigService.class);
        AiConfigService.ResolvedConfig config = new AiConfigService.ResolvedConfig(
                true, "http://127.0.0.1:11434/v1", "vendor/model", "",
                5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY, 2, 1, "test-key");
        when(configService.resolve()).thenReturn(config);
        doThrow(new ApiException("INVALID_AI_CONFIG", "unsafe destination"))
                .when(configService).validateOutboundBaseUrl(config.baseUrl());
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configService, new ObjectMapper(), RestClient.builder(), mock(SongFileRepository.class));

        assertThatThrownBy(() -> client.completeJson("BULK", "system", "user", 100))
                .isInstanceOf(ApiException.class)
                .hasMessage("unsafe destination");
    }

    private OpenAiCompatibleClient client(AiConfigService.JsonMode mode) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1", "vendor/model:any-v2", "",
                5, 0.97, 0.92, mode, 2, 1, "test-key"));
        return new OpenAiCompatibleClient(configService, new ObjectMapper(), RestClient.builder(), mock(SongFileRepository.class));
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/", exchange -> {
            try { handler.handle(exchange); }
            catch (Exception exception) {
                respond(exchange, 500, "{\"error\":{\"message\":\"test handler failed\"}}");
            }
            finally { exchange.close(); }
        });
        server.start();
    }

    private String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String completion(String content) throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", content)))));
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws Exception; }
}
