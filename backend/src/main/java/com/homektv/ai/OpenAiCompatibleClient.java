package com.homektv.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.homektv.domain.Song;
import com.homektv.domain.MediaImportRecord;
import com.homektv.repo.SongFileRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient {
    private final AiConfigService configService;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final RestClient.Builder restClientBuilder;
    private final SongFileRepository fileRepository;

    public OpenAiCompatibleClient(AiConfigService configService, ObjectMapper mapper, RestClient.Builder restClientBuilder,
                                 SongFileRepository fileRepository) {
        this.configService = configService;
        this.mapper = mapper;
        this.restClientBuilder = restClientBuilder;
        this.fileRepository = fileRepository;
    }

    public AiSongClassification classify(Song song, String role) {
        return parseClassification(completeJson(role, classificationSystemPrompt(), classificationUserPrompt(song), 1800));
    }

    public AiSongClassification classifyImport(MediaImportRecord record, String role) {
        String prompt = "导入记录 ID：" + record.getId() + "\n源文件：" + record.getSourceFilename()
                + "\n源路径：" + record.getSourcePath() + "\n本地歌名：" + record.getParsedTitle()
                + "\n本地歌手：" + record.getParsedArtist() + "\n媒体类型：" + record.getMediaType()
                + "\n格式：" + record.getSourceFormat() + "\n现有判断：" + record.getReason();
        return parseClassification(completeJson(role, classificationSystemPrompt(), prompt, 1800));
    }

    private AiSongClassification parseClassification(JsonNode value) {
        try {
            AiSongClassification result = mapper.treeToValue(value, AiSongClassification.class);
            validateClassification(result);
            return result;
        } catch (JsonProcessingException e) {
            throw new AiProviderException("AI_RESULT_INVALID", "AI 分类结果字段不兼容", e);
        }
    }

    private void validateClassification(AiSongClassification result) {
        if (result == null) throw new AiProviderException("AI_RESULT_INVALID", "AI 分类结果为空", null);
        if (length(result.title()) > 200 || length(result.artist()) > 200 || length(result.era()) > 80
                || length(result.ageRange()) > 80 || length(result.reason()) > 2000)
            throw new AiProviderException("AI_RESULT_INVALID", "AI 分类结果字段过长", null);
        if (result.genres().size() > 20 || result.themes().size() > 20 || result.recommendedPlaylists().size() > 20)
            throw new AiProviderException("AI_RESULT_INVALID", "AI 分类结果列表过长", null);
    }

    private int length(String value) { return value == null ? 0 : value.length(); }

    public JsonNode completeJson(String role, String systemPrompt, String userPrompt, int maxTokens) {
        configService.requireConfigured();
        AiConfigService.ResolvedConfig config = configService.resolve();
        boolean responseFormat = config.jsonMode() != AiConfigService.JsonMode.PROMPT_ONLY;
        try {
            return invokeJson(config, config.modelFor(role), systemPrompt, userPrompt, maxTokens, responseFormat);
        } catch (AiProviderException exception) {
            if (responseFormat && config.jsonMode() == AiConfigService.JsonMode.AUTO && exception.shouldRetryPromptOnly()) {
                return invokeJson(config, config.modelFor(role), systemPrompt, userPrompt, maxTokens, false);
            }
            throw exception;
        }
    }

    public JsonNode completeJsonPromptOnly(String role, String systemPrompt, String userPrompt, int maxTokens) {
        configService.requireConfigured();
        AiConfigService.ResolvedConfig config = configService.resolve();
        return invokeJson(config, config.modelFor(role), systemPrompt, userPrompt, maxTokens, false);
    }

    public List<String> listModels() {
        configService.requireConfigured();
        AiConfigService.ResolvedConfig config = configService.resolve();
        JsonNode response = request(config, "GET", "/models", null);
        List<String> models = new ArrayList<>();
        if (response == null || !response.path("data").isArray()) return List.of();
        for (JsonNode item : response.path("data")) {
            String id = item.path("id").asText("").trim();
            if (!id.isBlank()) models.add(id);
        }
        return models.stream().distinct().sorted().toList();
    }

    public Map<String, Object> testConnection() {
        configService.requireConfigured();
        AiConfigService.ResolvedConfig config = configService.resolve();
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("baseUrl", true);
        capabilities.put("authentication", true);
        try {
            capabilities.put("modelsEndpoint", true);
            capabilities.put("models", listModels());
        } catch (RuntimeException unsupported) {
            capabilities.put("modelsEndpoint", false);
            capabilities.put("models", List.of());
        }
        Map<String, Object> tested = new LinkedHashMap<>();
        tested.put("bulk", testModel(config.bulkModel(), "BULK"));
        if (config.reasoningModel() != null && !config.reasoningModel().isBlank()) {
            tested.put("reasoning", testModel(config.reasoningModel(), "REASONING"));
        }
        capabilities.put("chatCompletions", true);
        capabilities.put("jsonOutput", true);
        capabilities.put("testedModels", tested);
        configService.saveCapabilities(capabilities);
        return capabilities;
    }

    private Map<String, Object> testModel(String model, String role) {
        JsonNode json = completeJson(role, "Return JSON only.", "Return exactly {\"ok\":true}.", 40);
        if (!json.path("ok").asBoolean(false)) throw new AiProviderException("AI_JSON_INVALID", "模型未返回预期 JSON", null);
        return Map.of("model", model, "ok", true);
    }

    private JsonNode invokeJson(AiConfigService.ResolvedConfig config, String model, String systemPrompt,
                                String userPrompt, int maxTokens, boolean responseFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", Math.max(32, Math.min(maxTokens, 8000)));
        body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (responseFormat) body.put("response_format", Map.of("type", "json_object"));
        JsonNode response = request(config, "POST", "/chat/completions", body);
        JsonNode choice = response.path("choices").path(0);
        String content = responseContent(choice.path("message"));
        if (content.isBlank()) content = choice.path("text").asText("");
        if (content.isBlank()) content = response.path("output_text").asText("");
        if (content.isBlank()) throw new AiProviderException("AI_EMPTY_RESPONSE", "AI 服务未返回内容", null);
        try {
            return mapper.readTree(extractJson(content));
        } catch (JsonProcessingException e) {
            try {
                return tolerantMapper.readTree(extractJson(content));
            } catch (JsonProcessingException tolerantFailure) {
                throw new AiProviderException("AI_JSON_INVALID", "AI 服务返回的内容不是有效 JSON", tolerantFailure);
            }
        }
    }

    private String responseContent(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual() && !content.asText().isBlank()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                String value = part.isTextual() ? part.asText() : part.path("text").asText("");
                if (!value.isBlank()) text.append(value).append('\n');
            }
            return text.toString().trim();
        }
        String value = content.asText("");
        if (!value.isBlank()) return value;
        JsonNode reasoning = message.path("reasoning_content");
        return reasoning.isTextual() ? reasoning.asText() : "";
    }

    private JsonNode request(AiConfigService.ResolvedConfig config, String method, String path, Object body) {
        configService.validateOutboundBaseUrl(config.baseUrl());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30, config.timeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        RestClient client = restClientBuilder.baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                if ("GET".equals(method)) {
                    var req = client.get().uri(path);
                    if (config.apiKey() != null && !config.apiKey().isBlank()) {
                        req.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
                    }
                    return req.retrieve().body(JsonNode.class);
                }
                var req = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
                if (config.apiKey() != null && !config.apiKey().isBlank()) {
                    req.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
                }
                return req.retrieve().body(JsonNode.class);
            } catch (HttpStatusCodeException e) {
                last = e;
                int status = e.getStatusCode().value();
                if (!((status == 429 || status >= 500) && attempt < 3)) {
                    String code = status == 401 || status == 403 ? "AI_AUTH_FAILED" : status == 404 ? "AI_ENDPOINT_NOT_FOUND" : "AI_PROVIDER_ERROR";
                    throw new AiProviderException(code, "AI 服务请求失败（HTTP " + status + "）", e, status);
                }
                sleep(attempt);
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= 3) break;
                sleep(attempt);
            }
        }
        throw new AiProviderException("AI_PROVIDER_UNAVAILABLE", "AI 服务连接失败或请求超时", last);
    }

    private void sleep(int attempt) {
        try { Thread.sleep(500L * (1L << attempt)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AiProviderException("AI_INTERRUPTED", "AI 请求被中断", e); }
    }

    private String extractJson(String content) {
        String value = content.replaceAll("(?is)<think>.*?</think>", "").trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).trim();
        }
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        int start = objectStart < 0 ? arrayStart : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        if (start < 0) return value;
        char open = value.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == open) depth++;
            else if (current == close && --depth == 0) return value.substring(start, index + 1);
        }
        return value.substring(start);
    }

    private String classificationSystemPrompt() {
        return """
                你是家庭 KTV 曲库元数据修复器。只返回 JSON，不要 Markdown。不能确定时使用“未知”，禁止编造。
                JSON 字段：title, artist, artistGender, language, era, genres[], themes[], ageRange, vocalForm,
                recommendedPlaylists[], reason, confidence, titleConfidence, artistConfidence,
                languageConfidence, vocalFormConfidence, evidence{}。evidence 的值可以是字符串、数组或嵌套对象。
                language 只能是：国语、粤语、闽南语、英语、日语、韩语、纯音乐、其他、未知。
                vocalForm 只能是：独唱、对唱、合唱、组合、未知。对唱必须是两位不同主唱；三人以上是合唱，
                乐队是组合，双音轨不是对唱证据。置信度必须是 0 到 1。保留版本信息但不要把 MV/LIVE/分辨率当歌名。
                artistGender 只能是：男歌手、女歌手、组合、未知；多人或乐队使用“组合”，证据不足使用“未知”。
                当前语言若标记为 legacy_default/untrusted 就不是证据。
                """;
    }

    private String classificationUserPrompt(Song song) {
        boolean trustedLanguage = song.getMetadataProvenance() == null || !song.getMetadataProvenance().contains("legacy_default");
        return "歌曲 ID：" + song.getId() + "\n当前歌名：" + song.getTitle() + "\n当前歌手：" + song.getArtist()
                + "\n当前语言：" + song.getLanguage() + (trustedLanguage ? "（可信度未知）" : "（历史默认值，不可信）")
                + "\n演唱形式：" + song.getVocalForm() + "\n媒体类型：" + song.getMediaType()
                + "\n现有标签：" + String.join("、", song.getTags()) + "\n人工锁定字段：" + String.join("、", song.getMetadataLocks())
                + "\n文件证据：" + fileRepository.findBySongIdOrderByPriorityDesc(song.getId()).stream()
                .limit(5).map(file -> file.getFilePath() + (file.getSourcePath() == null ? "" : " | source=" + file.getSourcePath()))
                .toList();
    }

    public static class AiProviderException extends RuntimeException {
        private final String code;
        private final Integer status;
        AiProviderException(String code, String message, Throwable cause) { this(code, message, cause, null); }
        AiProviderException(String code, String message, Throwable cause, Integer status) { super(message, cause); this.code = code; this.status = status; }
        public String getCode() { return code; }
        boolean isCompatibilityFailure() { return status != null && (status == 400 || status == 404 || status == 422); }
        boolean shouldRetryPromptOnly() {
            return isCompatibilityFailure() || "AI_JSON_INVALID".equals(code) || "AI_EMPTY_RESPONSE".equals(code);
        }
    }
}
