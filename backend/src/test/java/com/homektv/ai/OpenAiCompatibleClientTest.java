package com.homektv.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
    void autoModeFallsBackOnUnprocessableEntityCompatibilityResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            String body = read(exchange);
            if (requests.getAndIncrement() == 0) {
                assertThat(body).contains("response_format");
                respond(exchange, 422, "{\"error\":\"json mode unsupported\"}");
            } else {
                assertThat(body).doesNotContain("response_format");
                respond(exchange, 200, completion("{\"ok\":true}"));
            }
        });

        assertThat(client(AiConfigService.JsonMode.AUTO)
                .completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void forceJsonModeDoesNotRetryWhenProviderRejectsResponseFormat() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            requests.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"unsupported\"}");
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.FORCE)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_PROVIDER_ERROR");
        assertThat(requests.get()).isEqualTo(1);
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
    void songClassificationSendsRelativeFileEvidenceWithoutAbsoluteLibraryPaths() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(read(exchange));
            respond(exchange, 200, completion("{}"));
        });
        Song song = new Song();
        song.setId(71L);
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        SongFile file = new SongFile();
        file.setSongId(71L);
        file.setFilePath("D:\\Private NAS Root\\library\\周杰伦\\晴天.mkv");
        file.setSourcePath("D:\\Private NAS Root\\imports\\晴天.mkv");
        file.setRelativePath("国语\\周杰伦\\晴天.mkv");
        SongFile fallbackFile = new SongFile();
        fallbackFile.setFilePath("/mnt/SECOND_PRIVATE_ROOT/山丘.mp4");
        fallbackFile.setRelativePath("/stale/absolute/path");
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(71L)).thenReturn(List.of(file, fallbackFile));

        client(AiConfigService.JsonMode.PROMPT_ONLY, files).classify(song, "BULK");

        assertThat(bodies).singleElement().asString()
                .contains("国语/周杰伦/晴天.mkv", "山丘.mp4")
                .doesNotContain("Private NAS Root", "D:\\\\Private NAS Root", "SECOND_PRIVATE_ROOT", "/stale/");
    }

    @Test
    void importClassificationDoesNotSendAbsolutePathsFromRecordOrReason() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(read(exchange));
            respond(exchange, 200, completion("{}"));
        });
        MediaImportRecord record = new MediaImportRecord();
        record.setId(72L);
        record.setSourcePath("D:\\Private NAS Root\\imports\\晴天.mkv");
        record.setSourceFilename("晴天.mkv");
        record.setParsedTitle("晴天");
        record.setParsedArtist("周杰伦");
        record.setReason("读取失败，文件位于 D:\\Private NAS Root\\imports\\晴天.mkv\n"
                + "容器文件位于 /mnt/private-library/imports/晴天.mkv\n"
                + "共享文件位于 \\\\NAS-SERVER\\Songs\\晴天.mkv");

        client(AiConfigService.JsonMode.PROMPT_ONLY).classifyImport(record, "BULK");

        assertThat(bodies).singleElement().asString()
                .contains("晴天.mkv", "读取失败", "容器文件位于")
                .doesNotContain("Private NAS Root", "D:\\\\Private NAS Root", "/mnt/private-library", "NAS-SERVER");
    }

    @Tag("extended")
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
    void rejectsAnOversizedValidProviderResponseBeforeParsingIt() throws Exception {
        start(exchange -> {
            read(exchange);
            String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],"
                    + "\"padding\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}";
            respond(exchange, 200, response);
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_RESPONSE_TOO_LARGE");
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

    @Test
    void testConnectionReportsUnsupportedModelsEndpointAndTestsOnlyBulkModel() throws Exception {
        List<String> authorizationHeaders = new ArrayList<>();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "{\"error\":\"unsupported\"}");
                return;
            }
            read(exchange);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, completion("{\"ok\":true}"));
        });
        OpenAiCompatibleClient client = client(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1",
                "bulk-model", " ", 5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY,
                2, 1, ""), mock(SongFileRepository.class));

        var result = client.testConnection();

        assertThat(result).containsEntry("modelsEndpoint", false).containsEntry("models", List.of());
        var testedModels = (java.util.Map<?, ?>) result.get("testedModels");
        assertThat(testedModels.get("bulk")).isEqualTo(java.util.Map.of("model", "bulk-model", "ok", true));
        assertThat(testedModels.size()).isEqualTo(1);
        assertThat(testedModels.containsKey("bulk")).isTrue();
        assertThat(authorizationHeaders).containsExactly((String) null);
    }

    @Test
    void testConnectionListsDistinctModelsAndTestsBothConfiguredModels() throws Exception {
        List<String> authorizationHeaders = new ArrayList<>();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 200, "{\"data\":[{\"id\":\"z-model\"},{\"id\":\" \"},"
                        + "{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}");
                return;
            }
            read(exchange);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, completion("{\"ok\":true}"));
        });
        OpenAiCompatibleClient client = client(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1",
                "bulk-model", "reasoning-model", 5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY,
                2, 1, "fixture-key"), mock(SongFileRepository.class));

        var result = client.testConnection();

        assertThat(result).containsEntry("modelsEndpoint", true)
                .containsEntry("models", List.of("a-model", "z-model"));
        var testedModels = (java.util.Map<?, ?>) result.get("testedModels");
        assertThat(testedModels.get("bulk")).isEqualTo(java.util.Map.of("model", "bulk-model", "ok", true));
        assertThat(testedModels.get("reasoning"))
                .isEqualTo(java.util.Map.of("model", "reasoning-model", "ok", true));
        assertThat(authorizationHeaders).containsExactly("Bearer fixture-key", "Bearer fixture-key");
    }

    @Test
    void testConnectionSkipsNullReasoningModel() throws Exception {
        AtomicInteger chatRequests = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "{}");
                return;
            }
            read(exchange);
            chatRequests.incrementAndGet();
            respond(exchange, 200, completion("{\"ok\":true}"));
        });
        OpenAiCompatibleClient client = client(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1",
                "bulk-model", null, 5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY,
                2, 1, null), mock(SongFileRepository.class));

        var result = client.testConnection();

        assertThat(((java.util.Map<?, ?>) result.get("testedModels")).size()).isEqualTo(1);
        assertThat(chatRequests.get()).isEqualTo(1);
    }

    @Test
    void testConnectionRejectsModelThatDoesNotReturnExpectedJson() throws Exception {
        start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "{}");
                return;
            }
            read(exchange);
            respond(exchange, 200, completion("{\"ok\":false}"));
        });
        OpenAiCompatibleClient client = client(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1",
                "bulk-model", null, 5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY,
                2, 1, "test-key"), mock(SongFileRepository.class));

        assertThatThrownBy(client::testConnection)
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_JSON_INVALID");
    }

    @Test
    void listModelsReturnsEmptyWhenResponseHasNoModelArray() throws Exception {
        AtomicInteger responseNumber = new AtomicInteger();
        start(exchange -> respond(exchange, 200, responseNumber.getAndIncrement() == 0
                ? "{}" : "{\"data\":\"not-an-array\"}"));
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY);

        assertThat(client.listModels()).isEmpty();
        assertThat(client.listModels()).isEmpty();
        assertThat(responseNumber.get()).isEqualTo(2);
    }

    @Test
    void listModelsReturnsEmptyWhenProviderBodyIsEmpty() throws Exception {
        start(exchange -> respond(exchange, 200, ""));

        assertThat(client(AiConfigService.JsonMode.PROMPT_ONLY).listModels()).isEmpty();
    }

    @Test
    void listModelsReturnsEmptyWhenJsonParserReturnsNull() throws Exception {
        start(exchange -> respond(exchange, 200, "{}"));
        ObjectMapper mapper = spy(new ObjectMapper());
        doReturn(null).when(mapper).readTree(any(byte[].class));
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1", "bulk-model", "",
                5, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY, 2, 1, "test-key"));
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configService, mapper, RestClient.builder(), mock(SongFileRepository.class));

        assertThat(client.listModels()).isEmpty();
    }

    @Test
    void rejectsUnauthorizedProviderResponseWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            requests.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_AUTH_FAILED")
                .hasFieldOrPropertyWithValue("status", 401);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void autoJsonModeDoesNotFallbackAfterAuthenticationFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(read(exchange));
            requests.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.AUTO)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_AUTH_FAILED");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(bodies).singleElement().asString().contains("response_format");
    }

    @Test
    void treatsForbiddenProviderResponseAsAuthenticationFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            requests.incrementAndGet();
            respond(exchange, 403, "{\"error\":\"forbidden\"}");
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_AUTH_FAILED")
                .hasFieldOrPropertyWithValue("status", 403);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void stopsRetryingServerErrorsAfterFourAttempts() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            requests.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_PROVIDER_ERROR")
                .hasFieldOrPropertyWithValue("status", 503);
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void retriesUnexpectedConnectionFailuresOnlyUpToTheConfiguredAttemptLimit() throws Exception {
        int unusedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        OpenAiCompatibleClient client = client(new AiConfigService.ResolvedConfig(
                true, "http://127.0.0.1:" + unusedPort + "/v1", "bulk-model", "",
                1, 0.97, 0.92, AiConfigService.JsonMode.PROMPT_ONLY, 2, 1, ""),
                mock(SongFileRepository.class));

        assertThatThrownBy(() -> client.completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_PROVIDER_UNAVAILABLE");
    }

    @Test
    void rejectsNullAndOverLimitClassificationFieldsAndLists() throws Exception {
        List<String> classifications = new ArrayList<>();
        classifications.add("null");
        for (var field : List.of(
                new Object[]{"title", 200}, new Object[]{"artist", 200},
                new Object[]{"era", 80}, new Object[]{"ageRange", 80},
                new Object[]{"reason", 2000})) {
            classifications.add(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    (String) field[0], "x".repeat((int) field[1] + 1))));
        }
        for (String field : List.of("genres", "themes", "recommendedPlaylists")) {
            classifications.add(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    field, java.util.Collections.nCopies(21, "fixture"))));
        }
        AtomicInteger responseNumber = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            respond(exchange, 200, completion(classifications.get(responseNumber.getAndIncrement())));
        });
        Song song = new Song();
        song.setId(81L);
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(81L)).thenReturn(List.of());
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY, files);

        for (int index = 0; index < classifications.size(); index++) {
            assertThatThrownBy(() -> client.classify(song, "BULK"))
                    .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                    .hasFieldOrPropertyWithValue("code", "AI_RESULT_INVALID");
        }
        assertThat(responseNumber.get()).isEqualTo(classifications.size());
    }

    @Test
    void readsOutputTextAndExtractsNestedArrayJsonWithEscapedQuotes() throws Exception {
        AtomicInteger responseNumber = new AtomicInteger();
        String array = new ObjectMapper().writeValueAsString(List.of(
                java.util.Map.of("text", "contains } and { and an escaped quote: \\\"", "ok", true)));
        start(exchange -> {
            read(exchange);
            switch (responseNumber.getAndIncrement()) {
                case 0 -> respond(exchange, 200, "{\"output_text\":\"{\\\"ok\\\":true}\"}");
                case 1 -> respond(exchange, 200, completion("provider note: " + array));
                case 2 -> respond(exchange, 200, completion("provider note: {\"outer\":{\"ok\":true}}"));
                case 3 -> respond(exchange, 200, completion("```{\"ok\":true}```"));
                default -> respond(exchange, 200, completion("```\n{\"ok\":true}"));
            }
        });
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY);

        assertThat(client.completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        JsonNode parsedArray = client.completeJson("BULK", "system", "user", 100);
        assertThat(parsedArray.isArray()).isTrue();
        assertThat(parsedArray.path(0).path("ok").asBoolean()).isTrue();
        assertThat(parsedArray.path(0).path("text").asText()).contains("}", "{");
        assertThat(client.completeJson("BULK", "system", "user", 100)
                .path("outer").path("ok").asBoolean()).isTrue();
        assertThat(client.completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        assertThat(client.completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        assertThat(responseNumber.get()).isEqualTo(5);
    }

    @Test
    void classificationRedactsAbsoluteEvidencePathsAndMarksLegacyLanguageUntrusted() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(read(exchange));
            respond(exchange, 200, completion("{}"));
        });
        Song song = new Song();
        song.setId(82L);
        song.setMetadataProvenance("legacy_default");
        List<SongFile> evidence = new ArrayList<>();
        evidence.add(fileEvidence(null, null));
        evidence.add(fileEvidence("  ", "  "));
        evidence.add(fileEvidence("D:\\PrivateDriveRoot\\drive-song.mkv",
                "D:\\StaleDriveRoot\\drive-song.mkv"));
        evidence.add(fileEvidence("\\\\PRIVATE-NAS\\Songs\\unc-song.mkv",
                "\\\\PRIVATE-NAS\\Songs\\unc-song.mkv"));
        evidence.add(fileEvidence("\\SecretRoot\\rooted-song.mkv", "\\SecretRoot\\rooted-song.mkv"));
        Song relativeSong = new Song();
        relativeSong.setId(83L);
        relativeSong.setMetadataProvenance("embedded_tag");
        Song missingProvenanceSong = new Song();
        missingProvenanceSong.setId(84L);
        missingProvenanceSong.setMetadataProvenance(null);
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findBySongIdOrderByPriorityDesc(82L)).thenReturn(evidence);
        when(files.findBySongIdOrderByPriorityDesc(83L)).thenReturn(List.of(
                fileEvidence("relative\\folder\\relative-song.mkv", "relative\\folder\\relative-song.mkv")));
        when(files.findBySongIdOrderByPriorityDesc(84L)).thenReturn(List.of());

        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY, files);
        client.classify(song, "BULK");
        client.classify(relativeSong, "BULK");
        client.classify(missingProvenanceSong, "BULK");

        assertThat(bodies).hasSize(3);
        assertThat(bodies.get(0))
                .contains("历史默认值，不可信", "drive-song.mkv", "unc-song.mkv", "rooted-song.mkv")
                .doesNotContain("PrivateDriveRoot", "StaleDriveRoot", "PRIVATE-NAS", "SecretRoot");
        assertThat(bodies.get(1)).contains("relative/folder/relative-song.mkv", "（可信度未知）");
        assertThat(bodies.get(2)).contains("（可信度未知）");
    }

    @Test
    void providerCompatibilityClassificationCoversAcceptedAndRejectedStatuses() {
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_PROVIDER_ERROR", "400", null, 400)
                .isCompatibilityFailure()).isTrue();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_PROVIDER_ERROR", "404", null, 404)
                .isCompatibilityFailure()).isTrue();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_PROVIDER_ERROR", "422", null, 422)
                .isCompatibilityFailure()).isTrue();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_PROVIDER_ERROR", "401", null, 401)
                .isCompatibilityFailure()).isFalse();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_PROVIDER_ERROR", "none", null)
                .isCompatibilityFailure()).isFalse();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_JSON_INVALID", "bad", null)
                .shouldRetryPromptOnly()).isTrue();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_EMPTY_RESPONSE", "empty", null)
                .shouldRetryPromptOnly()).isTrue();
        assertThat(new OpenAiCompatibleClient.AiProviderException("AI_AUTH_FAILED", "auth", null, 401)
                .shouldRetryPromptOnly()).isFalse();
    }

    @Test
    void rejectsEmptyAndMalformedProviderContentWithDistinctErrors() throws Exception {
        AtomicInteger responseNumber = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            int responseIndex = responseNumber.getAndIncrement();
            if (responseIndex == 0) {
                respond(exchange, 200, completion(""));
            } else if (responseIndex == 1) {
                respond(exchange, 200, completion("plain text without JSON"));
            } else {
                respond(exchange, 200, completion("prefix {\"ok\":true"));
            }
        });
        OpenAiCompatibleClient client = client(AiConfigService.JsonMode.PROMPT_ONLY);

        assertThatThrownBy(() -> client.completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_EMPTY_RESPONSE");
        assertThatThrownBy(() -> client.completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_JSON_INVALID");
        assertThatThrownBy(() -> client.completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_JSON_INVALID");
        assertThat(responseNumber.get()).isEqualTo(3);
    }

    @Test
    void responseContentSkipsBlankTextSegmentsBeforeUsingObjectText() throws Exception {
        AtomicInteger responseNumber = new AtomicInteger();
        start(exchange -> {
            read(exchange);
            if (responseNumber.getAndIncrement() == 0) {
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":[\"  \","
                        + "{\"type\":\"text\",\"text\":\"prefix {\\\"ok\\\":true}\"}]}}]}");
            } else {
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":123}}]}");
            }
        });

        assertThat(client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100).path("ok").asBoolean()).isTrue();
        assertThat(client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100).asInt()).isEqualTo(123);
        assertThat(responseNumber.get()).isEqualTo(2);
    }

    @Test
    void readsChunkedResponseUntilTheActualByteLimitIsExceeded() throws Exception {
        start(exchange -> {
            read(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] body = ("{\"padding\":\"" + "x".repeat(OpenAiCompatibleClient.MAX_RESPONSE_BYTES)
                    + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseBody().write(body);
        });

        assertThatThrownBy(() -> client(AiConfigService.JsonMode.PROMPT_ONLY)
                .completeJson("BULK", "system", "user", 100))
                .isInstanceOf(OpenAiCompatibleClient.AiProviderException.class)
                .hasFieldOrPropertyWithValue("code", "AI_RESPONSE_TOO_LARGE");
    }

    private OpenAiCompatibleClient client(AiConfigService.JsonMode mode) {
        return client(mode, mock(SongFileRepository.class));
    }

    private OpenAiCompatibleClient client(AiConfigService.JsonMode mode, SongFileRepository files) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(
                true, "http://localhost:" + server.getAddress().getPort() + "/v1", "vendor/model:any-v2", "",
                5, 0.97, 0.92, mode, 2, 1, "test-key"));
        return new OpenAiCompatibleClient(configService, new ObjectMapper(), RestClient.builder(), files);
    }

    private OpenAiCompatibleClient client(AiConfigService.ResolvedConfig resolved, SongFileRepository files) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(resolved);
        return new OpenAiCompatibleClient(configService, new ObjectMapper(), RestClient.builder(), files);
    }

    private SongFile fileEvidence(String filePath, String relativePath) {
        SongFile file = new SongFile();
        file.setFilePath(filePath);
        file.setRelativePath(relativePath);
        return file;
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
