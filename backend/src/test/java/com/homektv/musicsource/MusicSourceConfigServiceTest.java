package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicSourceConfigServiceTest {
    private static final String CONFIG_QUERY = "SELECT value::text FROM settings WHERE key = ?";

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private MusicSourceConfigService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mapper = new ObjectMapper();
        service = new MusicSourceConfigService(jdbc, mapper, 37);
    }

    @Test
    void returnsDefaultsWhenNoStoredConfigExists() {
        stubConfig(List.of());

        assertThat(service.getConfig()).isEqualTo(MusicSourceConfig.defaults());
    }

    @Test
    void readsStoredValuesAndIgnoresUnknownProviderNamesAndMalformedNumbers() {
        stubConfig(List.of("""
                {"enabled":true,"providers":["qq","not-a-provider"],"resultLimit":12,
                 "timeoutSeconds":"invalid","searchCacheHours":8,"concurrencyLimit":3,
                 "requestIntervalMs":2500,"autoApplyThreshold":0.75}
                """));

        assertThat(service.getConfig()).isEqualTo(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 12, 5, 8, 3, 2500, 0.75));
    }

    @Test
    void ignoresProviderNamesWhenStoredProvidersValueIsNotAnArray() {
        stubConfig(List.of("""
                {"enabled":true,"providers":"QQ","resultLimit":12}
                """));

        assertThat(service.getConfig()).isEqualTo(new MusicSourceConfig(
                true, Set.of(), 12, 5, 6, 1, 1500, 0.95));
    }

    @Test
    void fallsBackToDefaultsWhenStoredJsonCannotBeParsed() {
        stubConfig(List.of("{broken json"));

        assertThat(service.getConfig()).isEqualTo(MusicSourceConfig.defaults());
    }

    @Test
    void rejectsNullAndOutOfRangeSettingsBeforeWriting() {
        assertThatThrownBy(() -> service.save(null))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("MUSIC_SOURCE_CONFIG_INVALID"));

        List<MusicSourceConfig> invalid = List.of(
                config(0, 5, 6, 1, 1500, 0.95), config(51, 5, 6, 1, 1500, 0.95),
                config(20, 1, 6, 1, 1500, 0.95), config(20, 31, 6, 1, 1500, 0.95),
                config(20, 5, 0, 1, 1500, 0.95), config(20, 5, 169, 1, 1500, 0.95),
                config(20, 5, 6, 0, 1500, 0.95), config(20, 5, 6, 5, 1500, 0.95),
                config(20, 5, 6, 1, 499, 0.95), config(20, 5, 6, 1, 30001, 0.95),
                config(20, 5, 6, 1, 1500, 0.49), config(20, 5, 6, 1, 1500, 1.01));

        for (MusicSourceConfig value : invalid) {
            assertThatThrownBy(() -> service.save(value))
                    .as("invalid config %s", value)
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("MUSIC_SOURCE_CONFIG_INVALID"));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void normalizesNullProvidersAndPersistsTheNormalizedSettings() throws Exception {
        MusicSourceConfig saved = service.save(new MusicSourceConfig(true, null, 10, 4, 12, 2, 2000, 0.8));

        assertThat(saved.providers()).isEmpty();
        verify(jdbc).update(eq("INSERT INTO settings(key,value) VALUES (?,CAST(? AS jsonb)) ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value"),
                eq("music_sources.config"), anyString());
    }

    @Test
    void mapsPersistenceFailuresToThePublicConfigError() {
        doThrow(new IllegalStateException("db unavailable"))
                .when(jdbc).update(anyString(), eq("music_sources.config"), anyString());

        assertThatThrownBy(() -> service.save(config(20, 5, 6, 1, 1500, 0.95)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("MUSIC_SOURCE_CONFIG_SAVE_FAILED"));
    }

    @Test
    void exposesAStatusRowForEveryProviderAndClampsDailyLimitToOne() {
        stubEmptyProviderStates();
        MusicSourceConfigService limited = new MusicSourceConfigService(jdbc, mapper, 0);

        List<Map<String, Object>> states = limited.states();

        assertThat(states).hasSize(3);
        assertThat(states).extracting(row -> row.get("provider")).containsExactly("NETEASE", "QQ", "KUGOU");
        assertThat(states).allSatisfy(row -> {
            assertThat(row.get("healthy")).isEqualTo(false);
            assertThat(row.get("requestCount")).isEqualTo(0);
            assertThat(row.get("dailyLimit")).isEqualTo(1);
            assertThat(row.get("displayName")).isNotNull();
            assertThat(row).containsKeys("lastSuccessAt", "lastErrorAt", "lastError", "nextRequestAt", "cooldownUntil");
        });
    }

    @Test
    void fillsMissingProviderStateFieldsAndPreservesStoredState() throws Exception {
        OffsetDateTime lastSuccess = OffsetDateTime.parse("2026-01-02T03:04:05Z");
        doAnswer(invocation -> {
            RowCallbackHandler callback = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("provider")).thenReturn("QQ");
            when(row.getObject("healthy")).thenReturn(true);
            when(row.getObject("last_success_at", OffsetDateTime.class)).thenReturn(lastSuccess);
            when(row.getObject("last_error_at", OffsetDateTime.class)).thenReturn(null);
            when(row.getString("last_error")).thenReturn(null);
            when(row.getObject("next_request_at", OffsetDateTime.class)).thenReturn(null);
            when(row.getObject("cooldown_until", OffsetDateTime.class)).thenReturn(null);
            when(row.getInt("request_count")).thenReturn(6);
            callback.processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        List<Map<String, Object>> states = service.states();
        Map<String, Object> qq = states.stream().filter(row -> row.get("provider").equals("QQ")).findFirst().orElseThrow();

        assertThat(qq).containsEntry("healthy", true).containsEntry("lastSuccessAt", lastSuccess)
                .containsEntry("requestCount", 6).containsEntry("dailyLimit", 37).containsEntry("displayName", "QQ 音乐");
        assertThat(qq).containsEntry("lastErrorAt", null).containsEntry("lastError", null)
                .containsEntry("nextRequestAt", null).containsEntry("cooldownUntil", null);
        assertThat(states.stream().filter(row -> !row.get("provider").equals("QQ")))
                .allSatisfy(row -> assertThat(row.get("healthy")).isEqualTo(false));
    }

    @Test
    void configViewIncludesPublicSettingsAndProviderStatuses() {
        stubConfig(List.of("{\"enabled\":true,\"providers\":[\"NETEASE\"]}"));
        stubEmptyProviderStates();

        Map<String, Object> view = service.configView();

        assertThat(view).containsEntry("enabled", true).containsEntry("providers", List.of("NETEASE"))
                .containsEntry("resultLimit", 20).containsEntry("timeoutSeconds", 5)
                .containsEntry("searchCacheHours", 6).containsEntry("concurrencyLimit", 1)
                .containsEntry("requestIntervalMs", 1500).containsEntry("autoApplyThreshold", 0.95);
        assertThat((List<?>) view.get("providerStatus")).hasSize(3);
    }

    @Test
    void recordsSuccessAndSanitizesMissingProviderErrors() {
        service.recordSuccess(MusicProvider.QQ);
        service.recordError(MusicProvider.KUGOU, null);

        verify(jdbc).update(anyString(), eq("QQ"));
        verify(jdbc).update(anyString(), eq("KUGOU"), eq("未知错误"));
    }

    @Test
    void storesSanitizedProviderErrorWhenTextIsPresent() {
        service.recordError(MusicProvider.NETEASE, "  source failed\u0001  ");

        verify(jdbc).update(anyString(), eq("NETEASE"), eq("source failed"));
    }

    @Test
    void reusesAnExistingKugouDeviceIdWithoutWriting() {
        stubDeviceIds(List.of("ABC123"));

        assertThat(service.getOrCreateKugouDeviceId()).isEqualTo("ABC123");

        verify(jdbc).query(eq("SELECT anonymous_device_id FROM music_source_provider_state WHERE provider=?"),
                any(RowMapper.class), eq("KUGOU"));
        verifyNoInteractionsOnUpdate();
    }

    @Test
    void createsADeviceIdWhenTheStoredValueIsBlank() {
        stubDeviceIds(List.of("  "));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("KUGOU"))).thenReturn("GENERATEDDEVICEID");

        String id = service.getOrCreateKugouDeviceId();

        assertThat(id).isEqualTo("GENERATEDDEVICEID");
        verify(jdbc).update(anyString(), eq("KUGOU"), anyString());
        verify(jdbc).queryForObject(anyString(), eq(String.class), eq("KUGOU"));
    }

    @Test
    void createsADeviceIdWhenNoStoredRowExists() {
        stubDeviceIds(List.of());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("KUGOU"))).thenReturn("NEWDEVICEID");

        assertThat(service.getOrCreateKugouDeviceId()).isEqualTo("NEWDEVICEID");
        verify(jdbc).update(anyString(), eq("KUGOU"), anyString());
    }

    @Test
    void treatsANullStoredDeviceIdAsMissing() {
        stubDeviceIds(Collections.singletonList(null));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("KUGOU"))).thenReturn("NEWDEVICEID");

        assertThat(service.getOrCreateKugouDeviceId()).isEqualTo("NEWDEVICEID");
        verify(jdbc).update(anyString(), eq("KUGOU"), anyString());
    }

    private void stubConfig(List<String> values) {
        when(jdbc.query(eq(CONFIG_QUERY), any(RowMapper.class), eq("music_sources.config"))).thenReturn(values);
    }

    private void stubEmptyProviderStates() {
        doAnswer(invocation -> null).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
    }

    private void stubDeviceIds(List<String> ids) {
        when(jdbc.query(eq("SELECT anonymous_device_id FROM music_source_provider_state WHERE provider=?"),
                any(RowMapper.class), eq("KUGOU"))).thenReturn(ids);
    }

    private void verifyNoInteractionsOnUpdate() {
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    private static MusicSourceConfig config(int limit, int timeout, int cacheHours, int concurrency,
                                            int interval, double threshold) {
        return new MusicSourceConfig(true, Set.of(MusicProvider.QQ), limit, timeout, cacheHours, concurrency, interval, threshold);
    }
}
