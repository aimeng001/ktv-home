package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingServiceTest {

    private final SettingRepository repository = mock(SettingRepository.class);
    private final SettingService service = new SettingService(repository, new ObjectMapper());

    @Test
    void externalAudioLayoutDefaultsToNormalAndAcceptsDualChannel() {
        assertThat(service.externalDefaultAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        Setting stored = new Setting(SettingService.EXTERNAL_DEFAULT_AUDIO_LAYOUT, "\"DUAL_CHANNEL\"");
        when(repository.findAll()).thenReturn(java.util.List.of(stored));
        assertThat(service.externalDefaultAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
    }

    @Test
    void rejectsAnUnsupportedExternalAudioLayout() {
        assertThatThrownBy(() -> service.putAll(Map.of(
                        SettingService.EXTERNAL_DEFAULT_AUDIO_LAYOUT, "NOT_A_LAYOUT")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("音频布局");
    }
}
