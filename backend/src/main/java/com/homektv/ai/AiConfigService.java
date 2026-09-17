package com.homektv.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.AppSecret;
import com.homektv.domain.Setting;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AiConfigService {
    private static final String SECRET_KEY = "ai.api_key";
    private static final String PREFIX = "ai.";
    private final AppProperties properties;
    private final SettingRepository settings;
    private final AppSecretRepository secrets;
    private final SecretCryptoService crypto;
    private final ObjectMapper mapper;

    public AiConfigService(AppProperties properties, SettingRepository settings, AppSecretRepository secrets,
                           SecretCryptoService crypto, ObjectMapper mapper) {
        this.properties = properties;
        this.settings = settings;
        this.secrets = secrets;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ConfigResponse response() {
        ResolvedConfig config = resolve();
        Map<String, Object> capabilities = setting("capabilities", Map.of(), new TypeReference<>() {});
        String lastTestAt = setting("last_test_at", "", String.class);
        return new ConfigResponse(config.enabled(), config.baseUrl(), config.bulkModel(), config.reasoningModel(),
                config.timeoutSeconds(), config.identityThreshold(), config.classificationThreshold(), config.jsonMode(),
                config.bulkConcurrency(), config.reasoningConcurrency(), config.apiKey() != null && !config.apiKey().isBlank(),
                suffix(config.apiKey()), sourceMap(), capabilities, lastTestAt.isBlank() ? null : lastTestAt);
    }

    @Transactional(readOnly = true)
    public ResolvedConfig resolve() {
        AppProperties.Ai env = properties.getAi();
        String key = secrets.findById(SECRET_KEY)
                .map(secret -> crypto.decrypt(SECRET_KEY, secret.getCiphertext(), secret.getNonce()))
                .orElse(env.getApiKey());
        String bulkFallback = env.getBulkModel();
        return new ResolvedConfig(
                setting("enabled", env.isEnabled(), Boolean.class),
                normalizeBaseUrl(setting("base_url", env.getBaseUrl(), String.class)),
                setting("bulk_model", bulkFallback == null ? "" : bulkFallback, String.class).trim(),
                setting("reasoning_model", env.getReasoningModel() == null ? "" : env.getReasoningModel(), String.class).trim(),
                setting("timeout_seconds", env.getReadTimeoutSeconds(), Integer.class),
                setting("identity_threshold", 0.97d, Double.class),
                setting("classification_threshold", Math.max(0.92d, env.getAutoApplyConfidence()), Double.class),
                jsonMode(setting("json_mode", env.getJsonMode(), String.class)),
                setting("bulk_concurrency", env.getBulkConcurrency(), Integer.class),
                setting("reasoning_concurrency", env.getReasoningConcurrency(), Integer.class),
                key == null ? "" : key);
    }

    @Transactional
    public ConfigResponse update(ConfigUpdate update) {
        if (update == null) throw new ApiException("INVALID_AI_CONFIG", "AI 配置不能为空");
        String bulkModel = blank(update.bulkModel());
        String reasoningModel = blank(update.reasoningModel());
        validate(update, bulkModel, reasoningModel);
        put("enabled", update.enabled());
        put("base_url", normalizeBaseUrl(update.baseUrl()));
        put("bulk_model", bulkModel);
        put("reasoning_model", reasoningModel);
        put("timeout_seconds", update.timeoutSeconds());
        put("identity_threshold", update.identityThreshold());
        put("classification_threshold", update.classificationThreshold());
        put("json_mode", update.jsonMode() == null ? "AUTO" : update.jsonMode().toUpperCase());
        put("bulk_concurrency", update.bulkConcurrency());
        put("reasoning_concurrency", update.reasoningConcurrency());
        if (Boolean.TRUE.equals(update.clearApiKey())) secrets.deleteById(SECRET_KEY);
        if (update.apiKey() != null && !update.apiKey().isBlank()) {
            SecretCryptoService.EncryptedValue encrypted = crypto.encrypt(SECRET_KEY, update.apiKey().trim());
            AppSecret secret = secrets.findById(SECRET_KEY).orElseGet(AppSecret::new);
            secret.setKey(SECRET_KEY);
            secret.setCiphertext(encrypted.ciphertext());
            secret.setNonce(encrypted.nonce());
            secrets.save(secret);
        }
        return response();
    }

    @Transactional
    public void saveCapabilities(Map<String, Object> capabilities) {
        put("capabilities", capabilities == null ? Map.of() : capabilities);
        put("last_test_at", OffsetDateTime.now().toString());
    }

    public void requireConfigured() {
        ResolvedConfig config = resolve();
        if (!config.enabled()) throw new ApiException("AI_DISABLED", "AI 分析未启用，请先配置 AI 模型");
        if (config.baseUrl().isBlank()) throw new ApiException("AI_BASE_URL_MISSING", "AI API Base URL 未配置，请先配置 AI 模型");
        if (config.bulkModel().isBlank()) throw new ApiException("AI_MODEL_MISSING", "批量模型 ID 未配置，请先配置 AI 模型");
        validateOutboundBaseUrl(config.baseUrl());
        if (config.apiKey().isBlank() && !AiBaseUrlPolicy.isPrivateNetwork(config.baseUrl())) {
            throw new ApiException("AI_API_KEY_MISSING", "公网 AI 服务必须配置 API Key；无 Key 仅允许明确开启的本地私网模型");
        }
    }

    /** Validates the destination immediately before a request can carry the API key. */
    public void validateOutboundBaseUrl(String baseUrl) {
        AiBaseUrlPolicy.requireSafe(baseUrl, properties.getAi().isAllowPrivateNetwork());
    }

    /** Returns whether an AI request can be made without throwing a user-facing configuration error. */
    public boolean isConfigured() {
        ResolvedConfig config = resolve();
        if (!config.enabled() || config.baseUrl().isBlank() || config.bulkModel().isBlank()) {
            return false;
        }
        try {
            validateOutboundBaseUrl(config.baseUrl());
            if (config.apiKey().isBlank() && !AiBaseUrlPolicy.isPrivateNetwork(config.baseUrl())) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void validate(ConfigUpdate value, String bulkModel, String reasoningModel) {
        String baseUrl = value.baseUrl() == null ? "" : value.baseUrl().trim();
        if (Boolean.TRUE.equals(value.enabled()) && baseUrl.isBlank())
            throw new ApiException("INVALID_AI_CONFIG", "启用 AI 时必须填写 API Base URL");
        if (!baseUrl.isBlank()) AiBaseUrlPolicy.normalizeAndValidate(baseUrl, properties.getAi().isAllowPrivateNetwork());
        if (Boolean.TRUE.equals(value.enabled()) && bulkModel.isBlank())
            throw new ApiException("INVALID_AI_CONFIG", "批量模型 ID 不能为空且不能超过 200 个字符");
        if (bulkModel.length() > 200)
            throw new ApiException("INVALID_AI_CONFIG", "批量模型 ID 不能为空且不能超过 200 个字符");
        if (reasoningModel.length() > 200)
            throw new ApiException("INVALID_AI_CONFIG", "增强模型 ID 不能超过 200 个字符");
        if (value.timeoutSeconds() < 5 || value.timeoutSeconds() > 600)
            throw new ApiException("INVALID_AI_CONFIG", "请求超时必须在 5 到 600 秒之间");
        if (!between(value.identityThreshold()) || !between(value.classificationThreshold()))
            throw new ApiException("INVALID_AI_CONFIG", "自动应用阈值必须在 0 到 1 之间");
        try { JsonMode.valueOf((value.jsonMode() == null ? "AUTO" : value.jsonMode()).toUpperCase()); }
        catch (Exception e) { throw new ApiException("INVALID_AI_CONFIG", "JSON 模式无效"); }
        if (value.bulkConcurrency() < 1 || value.bulkConcurrency() > 10 || value.reasoningConcurrency() < 1 || value.reasoningConcurrency() > 5)
            throw new ApiException("INVALID_AI_CONFIG", "AI 并发数超出允许范围");
    }

    private boolean between(double value) { return value >= 0 && value <= 1; }

    private String normalizeBaseUrl(String value) {
        return AiBaseUrlPolicy.normalize(value);
    }

    private Map<String, String> sourceMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : new String[]{"enabled", "base_url", "bulk_model", "reasoning_model", "timeout_seconds",
                "identity_threshold", "classification_threshold", "json_mode", "bulk_concurrency", "reasoning_concurrency"}) {
            result.put(field, settings.existsById(PREFIX + field) ? "DATABASE" : hasEnvironment(field) ? "ENVIRONMENT" : "DEFAULT");
        }
        result.put("api_key", secrets.existsById(SECRET_KEY) ? "DATABASE" :
                (System.getenv("KTV_AI_API_KEY") != null && !System.getenv("KTV_AI_API_KEY").isBlank()) ? "ENVIRONMENT" : "NONE");
        return result;
    }

    private boolean hasEnvironment(String field) {
        String name = switch (field) {
            case "base_url" -> "KTV_AI_BASE_URL";
            case "bulk_model" -> System.getenv("KTV_AI_BULK_MODEL") != null ? "KTV_AI_BULK_MODEL" : "KTV_AI_MODEL";
            case "reasoning_model" -> "KTV_AI_REASONING_MODEL";
            case "timeout_seconds" -> "KTV_AI_READ_TIMEOUT_SECONDS";
            case "identity_threshold" -> "KTV_AI_IDENTITY_THRESHOLD";
            case "classification_threshold" -> "KTV_AI_AUTO_APPLY_CONFIDENCE";
            case "json_mode" -> "KTV_AI_JSON_MODE";
            case "bulk_concurrency" -> "KTV_AI_BULK_CONCURRENCY";
            case "reasoning_concurrency" -> "KTV_AI_REASONING_CONCURRENCY";
            default -> "KTV_AI_ENABLED";
        };
        return System.getenv(name) != null;
    }

    private <T> T setting(String key, T fallback, Class<T> type) {
        return settings.findById(PREFIX + key).map(Setting::getValue).map(json -> {
            try { return mapper.readValue(json, type); } catch (Exception ignored) { return fallback; }
        }).orElse(fallback);
    }

    private <T> T setting(String key, T fallback, TypeReference<T> type) {
        return settings.findById(PREFIX + key).map(Setting::getValue).map(json -> {
            try { return mapper.readValue(json, type); } catch (Exception ignored) { return fallback; }
        }).orElse(fallback);
    }

    private void put(String key, Object value) {
        Setting setting = settings.findById(PREFIX + key).orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(PREFIX + key);
            return created;
        });
        try { setting.setValue(mapper.writeValueAsString(value)); }
        catch (Exception e) { throw new IllegalStateException("AI 配置序列化失败", e); }
        settings.save(setting);
    }

    private String suffix(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    private JsonMode jsonMode(String value) {
        try { return JsonMode.valueOf(value == null ? "AUTO" : value.toUpperCase()); }
        catch (Exception ignored) { return JsonMode.AUTO; }
    }

    private String blank(String value) { return value == null ? "" : value.trim(); }

    public enum JsonMode { AUTO, FORCE, PROMPT_ONLY }
    public record ResolvedConfig(boolean enabled, String baseUrl, String bulkModel, String reasoningModel,
                                 int timeoutSeconds, double identityThreshold, double classificationThreshold,
                                 JsonMode jsonMode, int bulkConcurrency, int reasoningConcurrency, String apiKey) {
        public String modelFor(String role) {
            return "REASONING".equals(role) && reasoningModel != null && !reasoningModel.isBlank() ? reasoningModel : bulkModel;
        }
    }
    public record ConfigUpdate(boolean enabled, String baseUrl, String apiKey, Boolean clearApiKey,
                               String bulkModel, String reasoningModel, int timeoutSeconds,
                               double identityThreshold, double classificationThreshold, String jsonMode,
                               int bulkConcurrency, int reasoningConcurrency) { }
    public record ConfigResponse(boolean enabled, String baseUrl, String bulkModel, String reasoningModel,
                                 int timeoutSeconds, double identityThreshold, double classificationThreshold,
                                 JsonMode jsonMode, int bulkConcurrency, int reasoningConcurrency,
                                 boolean apiKeyConfigured, String apiKeySuffix, Map<String, String> sources,
                                 Map<String, Object> capabilities, String lastTestAt) { }
}
