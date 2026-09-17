package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class AiConfigValidationTest {
    private AppProperties properties;
    private SettingRepository settingRepo;
    private AppSecretRepository secretRepo;
    private SecretCryptoService cryptoService;
    private AiConfigService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        settingRepo = mock(SettingRepository.class);
        secretRepo = mock(AppSecretRepository.class);
        cryptoService = mock(SecretCryptoService.class);
        service = new AiConfigService(properties, settingRepo, secretRepo, cryptoService, new ObjectMapper());
    }

    @Test
    void enabledLocalAiMayBeUsableWithoutApiKey() {
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://127.0.0.1:11434/v1");
        properties.getAi().setBulkModel("local-model");
        properties.getAi().setApiKey("");
        properties.getAi().setAllowPrivateNetwork(true);

        assertThatCode(service::requireConfigured).doesNotThrowAnyException();
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void enabledPublicAiWithoutApiKeyIsRejectedBeforeAnyRequest() {
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("https://8.8.8.8/v1");
        properties.getAi().setBulkModel("public-model");
        properties.getAi().setApiKey("");

        assertThat(service.isConfigured()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(service::requireConfigured)
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "AI_API_KEY_MISSING");
    }

    @Test
    void isConfigured_returnsFalseWhenOutboundBaseUrlViolatesPolicy() {
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://192.168.1.50:11434/v1");
        properties.getAi().setBulkModel("qwen2.5");
        properties.getAi().setAllowPrivateNetwork(false);

        assertThat(service.isConfigured()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(service::requireConfigured)
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AI_CONFIG");
    }
}
