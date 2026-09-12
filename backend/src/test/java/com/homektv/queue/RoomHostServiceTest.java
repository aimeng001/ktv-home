package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.library.SettingService;
import com.homektv.repo.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomHostServiceTest {
    @Test
    void claimAndReleaseAdvanceRevisionForOutOfOrderEventRejection() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("room_host_user_id", 0L);
        settings.put("room_host_revision", 0L);
        SettingService settingService = mock(SettingService.class);
        doAnswer(invocation -> {
            settings.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settingService).putInternal(anyString(), org.mockito.ArgumentMatchers.any());
        when(settingService.getAll()).thenReturn(settings);

        AppUser user = new AppUser();
        user.setId(7L);
        user.setNickname("小明");
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findByClientToken("token")).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.existsById(7L)).thenReturn(true);

        RoomHostService service = new RoomHostService(settingService, users);

        assertThat(service.claim("token")).containsEntry("revision", 1L);
        assertThat(service.release("token")).containsEntry("revision", 2L);
    }
}
