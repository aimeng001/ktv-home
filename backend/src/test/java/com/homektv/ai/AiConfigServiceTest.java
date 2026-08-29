package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

    private static AiConfigService service() {
        return new AiConfigService(new AppProperties(), mock(SettingRepository.class),
                mock(AppSecretRepository.class), mock(SecretCryptoService.class), new ObjectMapper());
    }
}
