package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.AppSecret;
import com.homektv.domain.Setting;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiConfigServiceTest {

    @Test
    void disabledAiMayBeSavedWithoutAConfiguredModel() {
        AiConfigService service = service();

        assertThatCode(() -> service.update(new AiConfigService.ConfigUpdate(
                false, "", null, false, null, null, 10,
                0.97, 0.92, "AUTO", 2, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledAiStillRequiresABulkModel() {
        AiConfigService service = service();

        assertThatThrownBy(() -> service.update(new AiConfigService.ConfigUpdate(
                true, "https://8.8.8.8/v1", null, false, " ", null, 10,
                0.97, 0.92, "AUTO", 2, 1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("批量模型");
    }

    @Test
    void requireConfiguredReportsDisabledThenMissingBaseUrlThenMissingModel() {
        AppProperties properties = new AppProperties();
        AiConfigService service = service(properties, mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));

        assertThatThrownBy(service::requireConfigured)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AI_DISABLED"));

        properties.getAi().setEnabled(true);
        assertThatThrownBy(service::requireConfigured)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AI_BASE_URL_MISSING"));

        properties.getAi().setBaseUrl("https://8.8.8.8/v1");
        assertThatThrownBy(service::requireConfigured)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AI_MODEL_MISSING"));
    }

    @Test
    void allowsKeylessPrivateModelOnlyWhenPrivateNetworkIsExplicitlyAllowed() {
        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("https://8.8.8.8/v1");
        properties.getAi().setBulkModel("local-test-model");
        AiConfigService service = service(properties, mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));

        assertThatThrownBy(service::requireConfigured)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AI_API_KEY_MISSING"));
        assertThat(service.isConfigured()).isFalse();

        properties.getAi().setAllowPrivateNetwork(true);
        properties.getAi().setBaseUrl("http://127.0.0.1:11434/v1");
        assertThatCode(service::requireConfigured).doesNotThrowAnyException();
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfiguredRequiresCompleteSafePublicConfigurationAndRecognizesConfiguredKey() {
        AppProperties properties = new AppProperties();
        AiConfigService service = service(properties, mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));

        assertThat(service.isConfigured()).isFalse();
        properties.getAi().setEnabled(true);
        assertThat(service.isConfigured()).isFalse();
        properties.getAi().setBaseUrl("https://8.8.8.8/v1");
        assertThat(service.isConfigured()).isFalse();

        properties.getAi().setBulkModel("test-model");
        properties.getAi().setBaseUrl("http://8.8.8.8/v1");
        properties.getAi().setApiKey("configured-key");
        assertThat(service.isConfigured()).isFalse();

        properties.getAi().setBaseUrl("https://8.8.8.8/v1");
        assertThat(service.isConfigured()).isTrue();
        assertThatCode(service::requireConfigured).doesNotThrowAnyException();
    }

    @Test
    void updateRejectsInvalidModelTimeoutThresholdModeAndConcurrencyBoundsBeforeWrites() {
        SettingRepository settings = mock(SettingRepository.class);
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        AiConfigService service = service(new AppProperties(), settings, secrets, crypto);
        List<AiConfigService.ConfigUpdate> invalid = List.of(
                new AiConfigService.ConfigUpdate(true, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "m".repeat(201), null,
                        10, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", "r".repeat(201),
                        10, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "ftp://8.8.8.8/v1", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "http://127.0.0.1:11434/v1", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        4, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        601, 0.97, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, -0.01, 0.92, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 1.01, "AUTO", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "INVALID", 2, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 0, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 11, 1),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 2, 0),
                new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                        10, 0.97, 0.92, "AUTO", 2, 6));

        for (AiConfigService.ConfigUpdate update : invalid) {
            assertThatThrownBy(() -> service.update(update))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getCode()).isEqualTo("INVALID_AI_CONFIG"));
        }
        assertThatThrownBy(() -> service.update(null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_AI_CONFIG"));
        verifyNoInteractions(settings, secrets, crypto);
    }

    @Test
    void acceptsInclusiveConfigurationBoundariesAndNormalizesJsonMode() {
        AiConfigService.ConfigUpdate update = new AiConfigService.ConfigUpdate(false, "", null, false,
                "m".repeat(200), "r".repeat(200), 5, 0.0, 1.0, "prompt_only", 10, 5);

        assertThatCode(() -> service().update(update)).doesNotThrowAnyException();
    }

    @Test
    void updateDefaultsMissingJsonModeToAuto() {
        SettingRepository settings = mock(SettingRepository.class);
        AiConfigService service = service(new AppProperties(), settings,
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));

        service.update(new AiConfigService.ConfigUpdate(false, "", null, false, "bulk", null,
                10, 0.97, 0.92, null, 2, 1));

        org.mockito.ArgumentCaptor<Setting> saved = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settings, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).filteredOn(setting -> setting.getKey().equals("ai.json_mode"))
                .singleElement().extracting(Setting::getValue).isEqualTo("\"AUTO\"");
    }

    @Test
    void resolveUsesSafeFallbacksWhenEnvironmentDefaultsAreNull() {
        AppProperties properties = new AppProperties();
        properties.getAi().setModel(null);
        properties.getAi().setBulkModel(null);
        properties.getAi().setReasoningModel(null);
        properties.getAi().setJsonMode(null);

        AiConfigService.ResolvedConfig resolved = service(properties, mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class)).resolve();

        assertThat(resolved.bulkModel()).isEmpty();
        assertThat(resolved.reasoningModel()).isEmpty();
        assertThat(resolved.jsonMode()).isEqualTo(AiConfigService.JsonMode.AUTO);
    }

    @Test
    void updateTreatsNullBaseUrlAndBlankApiKeyAsEmptyValues() {
        SettingRepository settings = mock(SettingRepository.class);
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        AiConfigService service = service(new AppProperties(), settings, secrets, crypto);

        service.update(new AiConfigService.ConfigUpdate(false, null, "   ", false, "bulk", null,
                10, 0.97, 0.92, "AUTO", 2, 1));

        org.mockito.ArgumentCaptor<Setting> saved = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settings, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).filteredOn(setting -> setting.getKey().equals("ai.base_url"))
                .singleElement().extracting(Setting::getValue).isEqualTo("\"\"");
        verify(secrets, never()).deleteById("ai.api_key");
        verify(secrets, never()).save(any(AppSecret.class));
        verify(crypto, never()).encrypt(any(), any());
    }

    @Test
    void responseRevealsOnlySecretSuffixAndSavedCapabilities() {
        AppProperties properties = new AppProperties();
        AppSecret secret = new AppSecret();
        secret.setKey("ai.api_key");
        secret.setCiphertext(new byte[]{1});
        secret.setNonce(new byte[]{2});
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        when(secrets.findById("ai.api_key")).thenReturn(Optional.of(secret));
        when(secrets.existsById("ai.api_key")).thenReturn(true);
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        when(crypto.decrypt("ai.api_key", new byte[]{1}, new byte[]{2})).thenReturn("provider-secret-value");
        SettingRepository settings = mock(SettingRepository.class);
        when(settings.findById("ai.capabilities")).thenReturn(Optional.of(new Setting(
                "ai.capabilities", "{\"chatCompletions\":true}")));
        when(settings.findById("ai.last_test_at")).thenReturn(Optional.of(new Setting(
                "ai.last_test_at", "\"2026-10-19T10:00:00Z\"")));

        AiConfigService.ConfigResponse response = service(properties, settings, secrets, crypto).response();

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.apiKeySuffix()).isEqualTo("alue");
        assertThat(response.capabilities()).containsEntry("chatCompletions", true);
        assertThat(response.lastTestAt()).isEqualTo("2026-10-19T10:00:00Z");
        assertThat(response.toString()).doesNotContain("provider-secret-value");
        assertThat(response.sources()).containsEntry("api_key", "DATABASE");
    }

    @Test
    void responseDoesNotExposeShortSecretAsSuffix() {
        AppProperties shortKeyProperties = new AppProperties();
        shortKeyProperties.getAi().setApiKey("tiny");
        AiConfigService.ConfigResponse shortKeyResponse = service(shortKeyProperties,
                mock(SettingRepository.class), mock(AppSecretRepository.class),
                mock(SecretCryptoService.class)).response();
        assertThat(shortKeyResponse.apiKeyConfigured()).isTrue();
        assertThat(shortKeyResponse.apiKeySuffix()).isNull();
        assertThat(shortKeyResponse.toString()).doesNotContain("tiny");
    }

    @Test
    void responseReportsMissingSecretWithoutExposingFallbackText() {
        AppProperties absentKeyProperties = new AppProperties();
        absentKeyProperties.getAi().setApiKey(null);
        AiConfigService.ConfigResponse absentKeyResponse = service(absentKeyProperties,
                mock(SettingRepository.class), mock(AppSecretRepository.class),
                mock(SecretCryptoService.class)).response();
        assertThat(absentKeyResponse.apiKeyConfigured()).isFalse();
        assertThat(absentKeyResponse.apiKeySuffix()).isNull();
    }

    @Test
    void resolveUsesStoredOverridesAndFallsBackWhenSettingJsonIsMalformed() {
        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("https://fallback.example.test/v1");
        properties.getAi().setBulkModel("fallback-bulk");
        properties.getAi().setReasoningModel(" fallback-reasoning ");
        properties.getAi().setReadTimeoutSeconds(42);
        properties.getAi().setApiKey("fallback-key");
        SettingRepository settings = mock(SettingRepository.class);
        when(settings.findById("ai.enabled")).thenReturn(Optional.of(new Setting("ai.enabled", "not-json")));
        when(settings.findById("ai.base_url")).thenReturn(Optional.of(new Setting(
                "ai.base_url", "\"https://8.8.8.8/v1///\"")));
        when(settings.findById("ai.json_mode")).thenReturn(Optional.of(new Setting("ai.json_mode", "\"future-mode\"")));

        AiConfigService.ResolvedConfig resolved = service(properties, settings,
                mock(AppSecretRepository.class), mock(SecretCryptoService.class)).resolve();

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.baseUrl()).isEqualTo("https://8.8.8.8/v1");
        assertThat(resolved.bulkModel()).isEqualTo("fallback-bulk");
        assertThat(resolved.reasoningModel()).isEqualTo("fallback-reasoning");
        assertThat(resolved.timeoutSeconds()).isEqualTo(42);
        assertThat(resolved.jsonMode()).isEqualTo(AiConfigService.JsonMode.AUTO);
        assertThat(resolved.apiKey()).isEqualTo("fallback-key");
    }

    @Test
    void updateTrimsAndEncryptsNewKeyAndDeletesThePreviousOne() {
        SettingRepository settings = mock(SettingRepository.class);
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        AppSecret existing = new AppSecret();
        existing.setKey("ai.api_key");
        when(secrets.findById("ai.api_key")).thenReturn(Optional.of(existing));
        when(crypto.encrypt("ai.api_key", "new-secret-value"))
                .thenReturn(new SecretCryptoService.EncryptedValue(new byte[]{4}, new byte[]{5}));
        when(crypto.decrypt("ai.api_key", new byte[]{4}, new byte[]{5})).thenReturn("new-secret-value");
        AiConfigService service = service(new AppProperties(), settings, secrets, crypto);

        service.update(new AiConfigService.ConfigUpdate(false, "", " new-secret-value ", true,
                "bulk", null, 10, 0.97, 0.92, "AUTO", 2, 1));

        verify(secrets).deleteById("ai.api_key");
        verify(crypto).encrypt("ai.api_key", "new-secret-value");
        verify(secrets).save(existing);
        assertThat(existing.getKey()).isEqualTo("ai.api_key");
        assertThat(existing.getCiphertext()).containsExactly((byte) 4);
        assertThat(existing.getNonce()).containsExactly((byte) 5);
    }

    @Test
    void saveCapabilitiesTreatsNullAsEmptyAndUpdatesTestTimestamp() {
        SettingRepository settings = mock(SettingRepository.class);
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        AiConfigService service = service(new AppProperties(), settings, secrets,
                mock(SecretCryptoService.class));

        service.saveCapabilities(null);

        org.mockito.ArgumentCaptor<Setting> saved = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settings, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Setting::getKey)
                .containsExactly("ai.capabilities", "ai.last_test_at");
        assertThat(saved.getAllValues().get(0).getValue()).isEqualTo("{}");
        assertThat(saved.getAllValues().get(1).getValue()).contains("T");
        verify(settings).findById("ai.capabilities");
        verify(settings).findById("ai.last_test_at");
        verify(secrets, never()).save(any(AppSecret.class));
    }

    @Test
    void saveCapabilitiesPersistsProvidedCapabilityMap() {
        SettingRepository settings = mock(SettingRepository.class);
        AiConfigService service = service(new AppProperties(), settings,
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));

        service.saveCapabilities(java.util.Map.of("chatCompletions", true));

        org.mockito.ArgumentCaptor<Setting> saved = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settings, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0)).satisfies(capability -> {
            assertThat(capability.getKey()).isEqualTo("ai.capabilities");
            assertThat(capability.getValue()).isEqualTo("{\"chatCompletions\":true}");
        });
        assertThat(saved.getAllValues().get(1).getKey()).isEqualTo("ai.last_test_at");
    }

    private static AiConfigService service() {
        return service(new AppProperties(), mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class));
    }

    private static AiConfigService service(AppProperties properties, SettingRepository settings,
                                           AppSecretRepository secrets, SecretCryptoService crypto) {
        return new AiConfigService(properties, settings, secrets, crypto, new ObjectMapper());
    }
}
