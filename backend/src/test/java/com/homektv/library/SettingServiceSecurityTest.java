package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
