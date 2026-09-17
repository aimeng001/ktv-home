package com.homektv.musicsource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MusicSourceConfigService {
    private static final String KEY = "music_sources.config";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int dailyLimit;

    public MusicSourceConfigService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, 300);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MusicSourceConfigService(JdbcTemplate jdbc, ObjectMapper mapper,
                                    @Value("${app.music-provider-rate-limit.daily-limit:300}") int dailyLimit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    public MusicSourceConfig getConfig() {
        List<String> values = jdbc.query("SELECT value::text FROM settings WHERE key = ?", (rs, i) -> rs.getString(1), KEY);
        if (values.isEmpty()) return MusicSourceConfig.defaults();
        try {
            Map<String, Object> raw = mapper.readValue(values.getFirst(), new TypeReference<>() {});
            boolean enabled = Boolean.TRUE.equals(raw.get("enabled"));
            EnumSet<MusicProvider> providers = EnumSet.noneOf(MusicProvider.class);
            if (raw.get("providers") instanceof List<?> list) for (Object value : list) {
                try { providers.add(MusicProvider.parse(String.valueOf(value))); } catch (ApiException ignored) {}
            }
            return validate(new MusicSourceConfig(enabled, providers,
                    number(raw.get("resultLimit"), 20), number(raw.get("timeoutSeconds"), 5),
                    number(raw.get("searchCacheHours"), 6), number(raw.get("concurrencyLimit"), 1),
                    number(raw.get("requestIntervalMs"), 1500), decimal(raw.get("autoApplyThreshold"), 0.95)));
        } catch (Exception ex) {
            return MusicSourceConfig.defaults();
        }
    }

    @Transactional
    public MusicSourceConfig save(MusicSourceConfig config) {
        MusicSourceConfig valid = validate(config);
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "enabled", valid.enabled(), "providers", valid.providers().stream().map(Enum::name).toList(),
                    "resultLimit", valid.resultLimit(), "timeoutSeconds", valid.timeoutSeconds(),
                    "searchCacheHours", valid.searchCacheHours(), "concurrencyLimit", valid.concurrencyLimit(),
                    "requestIntervalMs", valid.requestIntervalMs(), "autoApplyThreshold", valid.autoApplyThreshold()));
            jdbc.update("INSERT INTO settings(key,value) VALUES (?,CAST(? AS jsonb)) ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value", KEY, json);
            return valid;
        } catch (Exception ex) {
            throw new ApiException("MUSIC_SOURCE_CONFIG_SAVE_FAILED", "音乐元数据设置保存失败");
        }
    }

    public Map<String, Object> configView() {
        MusicSourceConfig config = getConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", config.enabled());
        out.put("providers", config.providers().stream().map(Enum::name).toList());
        out.put("resultLimit", config.resultLimit());
        out.put("timeoutSeconds", config.timeoutSeconds());
        out.put("searchCacheHours", config.searchCacheHours());
        out.put("concurrencyLimit", config.concurrencyLimit());
        out.put("requestIntervalMs", config.requestIntervalMs());
        out.put("autoApplyThreshold", config.autoApplyThreshold());
        out.put("providerStatus", states());
        return out;
    }

    public List<Map<String, Object>> states() {
        Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(source.provider, rate.provider) AS provider,
                       source.healthy, source.last_success_at, source.last_error_at, source.last_error,
                       rate.next_request_at, rate.cooldown_until, rate.request_count
                FROM music_source_provider_state source
                FULL OUTER JOIN music_provider_rate_state rate ON rate.provider=source.provider
                ORDER BY 1
                """, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", rs.getString("provider"));
            row.put("healthy", Boolean.TRUE.equals(rs.getObject("healthy")));
            row.put("lastSuccessAt", rs.getObject("last_success_at", OffsetDateTime.class));
            row.put("lastErrorAt", rs.getObject("last_error_at", OffsetDateTime.class));
            row.put("lastError", rs.getString("last_error"));
            row.put("nextRequestAt", rs.getObject("next_request_at", OffsetDateTime.class));
            row.put("cooldownUntil", rs.getObject("cooldown_until", OffsetDateTime.class));
            row.put("requestCount", rs.getInt("request_count"));
            stored.put(rs.getString("provider"), row);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (MusicProvider provider : MusicProvider.values()) {
            Map<String, Object> row = stored.getOrDefault(provider.name(), new LinkedHashMap<>());
            row.putIfAbsent("provider", provider.name()); row.put("displayName", provider.displayName());
            row.putIfAbsent("healthy", false); row.putIfAbsent("lastSuccessAt", null);
            row.putIfAbsent("lastErrorAt", null); row.putIfAbsent("lastError", null);
            row.putIfAbsent("nextRequestAt", null); row.putIfAbsent("cooldownUntil", null);
            row.putIfAbsent("requestCount", 0); row.put("dailyLimit", dailyLimit);
            out.add(row);
        }
        return out;
    }

    @Transactional
    public void recordSuccess(MusicProvider provider) {
        jdbc.update("""
                INSERT INTO music_source_provider_state(provider,healthy,last_success_at,last_error,updated_at)
                VALUES (?,true,now(),NULL,now()) ON CONFLICT(provider) DO UPDATE
                SET healthy=true,last_success_at=now(),last_error=NULL,updated_at=now()
                """, provider.name());
    }

    @Transactional
    public void recordError(MusicProvider provider, String error) {
        String safe = ProviderJson.clean(error, 500);
        jdbc.update("""
                INSERT INTO music_source_provider_state(provider,healthy,last_error_at,last_error,updated_at)
                VALUES (?,false,now(),?,now()) ON CONFLICT(provider) DO UPDATE
                SET healthy=false,last_error_at=now(),last_error=EXCLUDED.last_error,updated_at=now()
                """, provider.name(), safe == null ? "未知错误" : safe);
    }

    @Transactional
    public String getOrCreateKugouDeviceId() {
        List<String> ids = jdbc.query("SELECT anonymous_device_id FROM music_source_provider_state WHERE provider=?", (rs, i) -> rs.getString(1), MusicProvider.KUGOU.name());
        if (!ids.isEmpty() && ids.getFirst() != null && !ids.getFirst().isBlank()) return ids.getFirst();
        String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.update("""
                INSERT INTO music_source_provider_state(provider,anonymous_device_id,updated_at) VALUES (?,?,now())
                ON CONFLICT(provider) DO UPDATE SET anonymous_device_id=COALESCE(music_source_provider_state.anonymous_device_id,EXCLUDED.anonymous_device_id),updated_at=now()
                """, MusicProvider.KUGOU.name(), id);
        return jdbc.queryForObject("SELECT anonymous_device_id FROM music_source_provider_state WHERE provider=?", String.class, MusicProvider.KUGOU.name());
    }

    private static MusicSourceConfig validate(MusicSourceConfig config) {
        if (config == null) throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "音乐元数据设置不能为空");
        if (config.resultLimit() < 1 || config.resultLimit() > 50)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "每个平台搜索数量必须在 1 到 50 之间");
        if (config.timeoutSeconds() < 2 || config.timeoutSeconds() > 30)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "请求超时必须在 2 到 30 秒之间");
        if (config.searchCacheHours() < 1 || config.searchCacheHours() > 168)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "搜索缓存必须在 1 到 168 小时之间");
        if (config.concurrencyLimit() < 1 || config.concurrencyLimit() > 4)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "单平台并发上限必须在 1 到 4 之间");
        if (config.requestIntervalMs() < 500 || config.requestIntervalMs() > 30000)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "同平台请求间隔必须在 500 到 30000 毫秒之间");
        if (config.autoApplyThreshold() < 0.5 || config.autoApplyThreshold() > 1)
            throw new ApiException("MUSIC_SOURCE_CONFIG_INVALID", "自动写入阈值必须在 0.5 到 1 之间");
        Set<MusicProvider> providers = config.providers() == null ? Set.of() : Set.copyOf(config.providers());
        return new MusicSourceConfig(config.enabled(), providers, config.resultLimit(), config.timeoutSeconds(),
                config.searchCacheHours(), config.concurrencyLimit(), config.requestIntervalMs(), config.autoApplyThreshold());
    }

    private static int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static double decimal(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
}
