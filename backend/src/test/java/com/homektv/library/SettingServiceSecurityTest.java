package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SettingServiceSecurityTest {
    private SettingRepository repo;
    private ObjectMapper mapper;
    private SettingService service;
    private List<Setting> storedSettings;

    @BeforeEach
    void setUp() {
        repo = mock(SettingRepository.class);
        mapper = new ObjectMapper();
        storedSettings = new ArrayList<>();

        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(storedSettings));
        when(repo.findById(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return storedSettings.stream().filter(s -> k.equals(s.getKey())).findFirst();
        });
        when(repo.save(any(Setting.class))).thenAnswer(inv -> {
            Setting s = inv.getArgument(0);
            storedSettings.removeIf(existing -> s.getKey().equals(existing.getKey()));
            storedSettings.add(s);
            return s;
        });

        service = new SettingService(repo, mapper);
    }

    @Test
    void putEditable_rejectsInternalKeys() {
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_logo_path", "secrets/config.key")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_NOT_ALLOWED");

        assertThatThrownBy(() -> service.putEditable(Map.of("room_host_user_id", 100L)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_NOT_ALLOWED");
    }

    @Test
    void getEditable_excludesInternalStateAndExposesDerivedFlag() {
        service.putInternal("standby_logo_path", "standby/logo-test.png");
        service.putInternal("room_host_user_id", 123L);

        Map<String, Object> editable = service.getEditable();
        assertThat(editable).doesNotContainKeys("standby_logo_path", "room_host_user_id");
        assertThat(editable).containsEntry("standby_logo_configured", true);
    }

    @Test
    void putInternal_acceptsInternalKeys() {
        service.putInternal("room_host_user_id", 456L);
        assertThat(service.getAll()).containsEntry("room_host_user_id", 456);
    }

    @Test
    void nullBatchWritesAreNoOpsAndInternalWriterRejectsEditableKeys() {
        service.putAll(null);
        service.putEditable(null);

        assertThatThrownBy(() -> service.putInternal("standby_interval_sec", 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非内部设置键");
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void getEditable_doesNotExposeLegacyQrAddress() {
        Map<String, Object> editable = service.getEditable();
        assertThat(editable).doesNotContainKey("qr_address");
    }

    @Test
    void putEditable_rejectsNonStringForTextSettings() {
        assertThatThrownBy(() -> service.putEditable(Map.of("display_address", 12345)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");

        assertThatThrownBy(() -> service.putEditable(Map.of("standby_welcome", List.of("bad"))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");
    }

    @Test
    void putEditable_rejectsTextOverTheLengthLimitAndNonBooleanFlags() {
        assertThatThrownBy(() -> service.putEditable(Map.of("display_address", "x".repeat(1001))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_RANGE");

        assertThatThrownBy(() -> service.putEditable(Map.of("standby_carousel", "true")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void putAll_rejectsNonStringAndUnknownDirectCopyOptions() {
        assertThatThrownBy(() -> service.putAll(Map.of("direct_copy_containers", "mkv")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");

        assertThatThrownBy(() -> service.putAll(Map.of(
                        "direct_copy_containers", List.of("mkv", 7))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");

        assertThatThrownBy(() -> service.putAll(Map.of(
                        "direct_copy_containers", List.of("file://source"))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
    }

    @Test
    void directCopyOptionsRejectBlankAndOverlongValues() {
        for (String invalid : List.of("   ", "x".repeat(33))) {
            assertThatThrownBy(() -> service.putAll(Map.of("direct_copy_containers", List.of(invalid))))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_RANGE");
        }
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void putAllRejectsUnsupportedPolicyEnums() {
        Map<String, Object> invalidSettings = Map.of(
                "transcode_output_container", "avi",
                "tv_video_scale_mode", "stretch",
                "standby_source", "random");

        for (Map.Entry<String, Object> entry : invalidSettings.entrySet()) {
            assertThatThrownBy(() -> service.putAll(Map.of(entry.getKey(), entry.getValue())))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
        }
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void externalDefaultAudioLayoutRequiresAStringValue() {
        assertThatThrownBy(() -> service.putAll(Map.of("external_default_audio_layout", 1)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void standbySongIdsRejectInvalidShapeValuesDuplicatesAndOversizedLists() {
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", "1,2")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", List.of(0L))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", List.of("7"))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", List.of(1.5))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", List.of(7L, 7L))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_VALUE");

        List<Long> oversized = new ArrayList<>();
        for (long id = 1; id <= SettingService.MAX_STANDBY_SONGS + 1L; id++) oversized.add(id);
        assertThatThrownBy(() -> service.putEditable(Map.of("standby_song_ids", oversized)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_RANGE");
        verify(repo, never()).save(any(Setting.class));
    }

    @Test
    void transcodePolicy_doesNotStringifyInvalidPersistedListElements() {
        Setting stored = new Setting("direct_copy_containers", "[1,\"mkv\"]");
        when(repo.findAll()).thenReturn(List.of(stored));

        assertThat(service.transcodePolicy().directCopyContainers()).containsExactly("mkv");
    }

    @Test
    void editableKeys_includeDiscoveryAndStructuredFilenamePreference() {
        assertThat(SettingService.EDITABLE_KEYS).contains(
                "library_filename_metadata_preferred",
                "discovery_enabled",
                "discovery_name"
        );
    }

    @Test
    void discoveryName_validatesTypeAndLength() {
        assertThatThrownBy(() -> service.putEditable(Map.of("discovery_name", 123)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");

        assertThatThrownBy(() -> service.putEditable(Map.of("discovery_name", "x".repeat(33))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_RANGE");
    }

    @Test
    void discoveryName_normalizesBlankToDefault() {
        service.putEditable(Map.of("discovery_name", "   "));
        assertThat(service.getDiscoveryName("fallback")).isEqualTo("家庭KTV");
    }

    @Test
    void appProperties_providesDefaultOverridesWhenDatabaseEmpty() {
        AppProperties props = new AppProperties();
        props.getDiscovery().setInstanceName("客厅点歌机");
        props.getDiscovery().setEnabled(false);
        props.setStructuredFilenameMetadataPreferred(true);

        when(repo.findAll()).thenReturn(List.of());
        SettingService customService = new SettingService(repo, new ObjectMapper(), props);

        assertThat(customService.getDiscoveryName("fallback")).isEqualTo("客厅点歌机");
        assertThat(customService.isDiscoveryEnabled(true)).isFalse();
        assertThat(customService.isFilenameMetadataPreferred(false)).isTrue();
    }

    @Test
    void settings_readPathUsesInMemoryCacheToPreventDatabaseFlooding() {
        when(repo.findAll()).thenReturn(List.of());
        SettingService cachedService = new SettingService(repo, new ObjectMapper());

        for (int i = 0; i < 10; i++) {
            cachedService.isFilenameMetadataPreferred(false);
            cachedService.getDiscoveryName("default");
            cachedService.isDiscoveryEnabled(true);
        }

        // repo.findAll() should be called at most once (for initial cache population), NOT 30 times
        verify(repo, atMostOnce()).findAll();
    }

    @Test
    void discoveryAndStructuredFilename_requireBooleanType() {
        assertThatThrownBy(() -> service.putEditable(Map.of("discovery_enabled", "true")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");

        assertThatThrownBy(() -> service.putEditable(Map.of("library_filename_metadata_preferred", "true")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_TYPE");
    }
}
