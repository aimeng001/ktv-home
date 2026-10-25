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

    private static final class MessageLessJsonProcessingException
            extends com.fasterxml.jackson.core.JsonProcessingException {
        private MessageLessJsonProcessingException() {
            super("placeholder");
        }

        @Override
        public String getMessage() {
            return null;
        }
    }

    private static final class BlankMessageJsonProcessingException
            extends com.fasterxml.jackson.core.JsonProcessingException {
        private BlankMessageJsonProcessingException() {
            super("placeholder");
        }

        @Override
        public String getMessage() {
            return " \t ";
        }
    }

    private final SettingRepository repository = mock(SettingRepository.class);
    private final SettingService service = new SettingService(repository, new ObjectMapper());

    @Test
    void externalAudioLayoutDefaultsToNormalAndAcceptsDualChannel() {
        assertThat(service.externalDefaultAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        Setting stored = new Setting(SettingService.EXTERNAL_DEFAULT_AUDIO_LAYOUT, "\"DUAL_CHANNEL\"");
        when(repository.findAll()).thenReturn(java.util.List.of(stored));
        assertThat(service.externalDefaultAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);

        when(repository.findAll()).thenReturn(List.of(
                new Setting(SettingService.EXTERNAL_DEFAULT_AUDIO_LAYOUT, "\"unknown\"")));
        assertThat(service.externalDefaultAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
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
    void getEditableFiltersInvalidLegacyStandbyIdsAndPreservesValidOrder() throws Exception {
        Setting stored = new Setting("standby_song_ids",
                new ObjectMapper().writeValueAsString(java.util.Arrays.asList(-1, 0, 1.5, "3", null, 3, 2, 3)));
        when(repository.findAll()).thenReturn(List.of(stored));

        Object value = service.getEditable().get("standby_song_ids");

        assertThat(value).isEqualTo(List.of(3L, 2L));
    }

    @Test
    void getEditableNormalizesNonArrayStandbyIdsAndBlankLogoState() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("standby_song_ids", "{\"unexpected\":true}"),
                new Setting("standby_logo_path", "\"  \"")));
        when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> editable = service.getEditable();

        assertThat(editable.get("standby_song_ids")).isEqualTo(List.of());
        assertThat(editable).containsEntry("standby_logo_configured", false);
        Map<String, Object> payload = new java.util.HashMap<>(editable);
        payload.remove("standby_logo_configured");
        assertThatCode(() -> service.putEditable(payload)).doesNotThrowAnyException();
    }

    @Test
    void getEditableMarksANonBlankLogoAsConfigured() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("standby_logo_path", "\"/data/assets/logo.png\"")));

        assertThat(service.getEditable()).containsEntry("standby_logo_configured", true);
    }

    @Test
    void getEditableTreatsANullLogoPathAsNotConfigured() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("standby_logo_path", "null")));

        assertThat(service.getEditable()).containsEntry("standby_logo_configured", false);
    }

    @Test
    void putAllRejectsUnknownKeys() {
        assertThatThrownBy(() -> service.putAll(Map.of("unknown_setting", true)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_NOT_ALLOWED");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }

    @Test
    void putAllTreatsNullAsNoOpAndPersistsAValidStringSetting() {
        service.putAll(null);
        when(repository.findById("display_address")).thenReturn(java.util.Optional.empty());

        service.putAll(Map.of("display_address", "living room"));

        org.mockito.ArgumentCaptor<Setting> saved = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getKey()).isEqualTo("display_address");
        assertThat(saved.getValue().getValue()).isEqualTo("\"living room\"");
    }

    @Test
    void getAllFiltersPrivateNamespacesAndKeepsMalformedLegacyValuesReadable() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("ai.api_key", "\"must-not-leak\""),
                new Setting("music_sources.provider.token", "\"must-not-leak\""),
                new Setting("display_address", "not-json"),
                new Setting("room_host_user_id", "123")));

        Map<String, Object> all = service.getAll();

        assertThat(all).doesNotContainKeys("ai.api_key", "music_sources.provider.token");
        assertThat(all).containsEntry("display_address", "not-json");
        assertThat(all).containsEntry("room_host_user_id", 123);
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
    void serializationFailureWithoutMessageReportsTheExceptionType() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString("bad-value"))
                .thenThrow(new MessageLessJsonProcessingException());
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findById("standby_welcome")).thenReturn(java.util.Optional.empty());
        SettingService service = new SettingService(repository, mapper);

        assertThatThrownBy(() -> service.putAll(Map.of("standby_welcome", "bad-value")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("JsonProcessingException");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void serializationFailureWithBlankMessageReportsTheExceptionType() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString("bad-value"))
                .thenThrow(new BlankMessageJsonProcessingException());
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findById("standby_welcome")).thenReturn(java.util.Optional.empty());
        SettingService service = new SettingService(repository, mapper);

        assertThatThrownBy(() -> service.putAll(Map.of("standby_welcome", "bad-value")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("BlankMessageJsonProcessingException");
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

        for (Object invalid : List.of(2, 61, "8")) {
            assertThatThrownBy(() -> service.putEditable(Map.of("standby_interval_sec", invalid)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SETTING_INVALID_RANGE"));
        }
        assertThatCode(() -> service.putEditable(Map.of("standby_interval_sec", 3))).doesNotThrowAnyException();
        assertThatCode(() -> service.putEditable(Map.of("standby_interval_sec", 60))).doesNotThrowAnyException();
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

    @Test
    void transcodePolicyFallsBackForMalformedPersistedOptionsAndEnums() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("direct_copy_containers", "\"not-an-array\""),
                new Setting("direct_copy_video_codecs", "[1,\"unsupported\"]"),
                new Setting("direct_copy_audio_codecs", "[]"),
                new Setting("transcode_output_container", "\"avi\""),
                new Setting("transcode_video_codec", "\"vp9\""),
                new Setting("transcode_audio_codec", "\"flac\""),
                new Setting("transcode_audio_only", "\"yes\"")));

        SettingService.TranscodePolicy policy = service.transcodePolicy();

        assertThat(policy.directCopyContainers()).containsExactly("mp4", "m4v", "mkv");
        assertThat(policy.directCopyVideoCodecs()).containsExactly("h264", "hevc");
        assertThat(policy.directCopyAudioCodecs()).containsExactly("aac", "mp3");
        assertThat(policy.outputContainer()).isEqualTo("mkv");
        assertThat(policy.videoCodec()).isEqualTo("h264");
        assertThat(policy.audioCodec()).isEqualTo("aac");
        assertThat(policy.transcodeAudioOnly()).isFalse();
    }

    @Test
    void directCopyPolicyNormalizesAndDeduplicatesSupportedOptions() {
        when(repository.findAll()).thenReturn(List.of(
                new Setting("direct_copy_containers", "[\" MKV \",\"mp4\",\"mkv\"]")));

        assertThat(service.transcodePolicy().directCopyContainers()).containsExactly("mkv", "mp4");
    }
}
