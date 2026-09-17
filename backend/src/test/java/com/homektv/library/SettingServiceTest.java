package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * 读路径必须归一化 standby_song_ids，保证 getEditable() 返回的值永远能被 putEditable() 回写。
     *
     * <p>否则只要库里残留脏值（历史版本写入或手工 SQL：超过 100 首、含重复），
     * 管理员即使只想改「轮播间隔」这类无关设置，整包回写也会被 400 拒绝，
     * 而且提示只会指向「基础设置」，无法自助恢复。
     */
    @Test
    void editableSettingsStayWritableWhenStoredStandbyIdsAreDirty() throws Exception {
        java.util.List<Long> dirty = new java.util.ArrayList<>();
        for (long id = 1; id <= 250; id++) {
            dirty.add(id);
        }
        dirty.add(7L);

        ObjectMapper mapper = new ObjectMapper();
        Setting stored = new Setting("standby_song_ids", mapper.writeValueAsString(dirty));
        when(repository.findAll()).thenReturn(java.util.List.of(stored));
        when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> editable = service.getEditable();

        assertThat(editable.get("standby_song_ids")).isInstanceOf(java.util.List.class);
        java.util.List<?> normalized = (java.util.List<?>) editable.get("standby_song_ids");
        assertThat(normalized).hasSize(SettingService.MAX_STANDBY_SONGS);
        assertThat(normalized).doesNotHaveDuplicates();
        // 保留原始顺序、只截断超限部分
        assertThat(normalized.get(0)).isEqualTo(1L);
        assertThat(normalized.get(SettingService.MAX_STANDBY_SONGS - 1)).isEqualTo(100L);

        // 读到的值必须能原样回写。standby_logo_configured 是派生只读字段，前端同样不会回传。
        Map<String, Object> payload = new java.util.HashMap<>(editable);
        payload.remove("standby_logo_configured");
        assertThatCode(() -> service.putEditable(payload)).doesNotThrowAnyException();
    }

    @Test
    void failsTheWholeWriteWhenASettingCannotBeSerialized() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString("bad-value"))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("serialization failed") { });
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findById("standby_welcome")).thenReturn(java.util.Optional.empty());
        SettingService service = new SettingService(repository, mapper);

        assertThatThrownBy(() -> service.putAll(Map.of("standby_welcome", "bad-value")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("设置序列化失败");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void putEditable_rejectsInvalidVideoOrAudioCodecs() {
        SettingRepository repository = mock(SettingRepository.class);
        SettingService service = new SettingService(repository, new ObjectMapper());

        assertThatThrownBy(() -> service.putEditable(Map.of("transcode_video_codec", "libx264")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SETTING_INVALID_VALUE"));

        assertThatThrownBy(() -> service.putEditable(Map.of("transcode_audio_codec", "flac")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SETTING_INVALID_VALUE"));
    }

    @Test
    void intervalSettingsRejectFractionalNumbersInsteadOfTruncatingThem() {
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_interval_sec", 3.5)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SETTING_INVALID_RANGE"));
    }

    @Test
    void resetTranscodeDefaults_returnsEditableWithoutInternalKeys() {
        SettingRepository repository = mock(SettingRepository.class);
        Setting storedInternal = new Setting("room_host_user_id", "123");
        when(repository.findAll()).thenReturn(List.of(storedInternal));
        SettingService service = new SettingService(repository, new ObjectMapper());

        Map<String, Object> result = service.resetTranscodeDefaults();

        assertThat(result).doesNotContainKey("room_host_user_id");
        assertThat(result).doesNotContainKey("standby_logo_path");
    }
}
