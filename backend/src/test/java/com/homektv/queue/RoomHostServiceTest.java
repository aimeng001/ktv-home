package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.library.SettingService;
import com.homektv.repo.AppUserRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void isHostRequiresAResolvedClientMatchingThePositiveHostId() {
        Fixture fixture = fixture();
        register(fixture, "host", 7L, "小明");
        register(fixture, "guest", 8L, "小红");
        fixture.settings().put("room_host_user_id", 7L);

        assertThat(fixture.service().isHost("host")).isTrue();
        assertThat(fixture.service().isHost("guest")).isFalse();
        assertThat(fixture.service().isHost(null)).isFalse();
        assertThat(fixture.service().isHost("  ")).isFalse();
        assertThat(fixture.service().isHost("unknown")).isFalse();

        fixture.settings().put("room_host_user_id", 0L);
        assertThat(fixture.service().isHost("host")).isFalse();
    }

    @Test
    void statusDoesNotExposeADeletedHostAndClampsNegativeNumericRevision() {
        Fixture fixture = fixture();
        fixture.settings().put("room_host_user_id", 99L);
        fixture.settings().put("room_host_revision", -4L);

        Map<String, Object> status = fixture.service().status(null);

        assertThat(status).containsEntry("claimed", false)
                .containsEntry("hostUserId", null)
                .containsEntry("hostNickname", null)
                .containsEntry("revision", 0L)
                .containsEntry("isHost", false);
        verify(fixture.users()).findById(99L);
    }

    @Test
    void claimRejectsAnotherActiveHostButAllowsSameAndStaleClaims() {
        Fixture fixture = fixture();
        register(fixture, "host", 7L, "小明");
        register(fixture, "guest", 8L, "小红");
        fixture.settings().put("room_host_user_id", 7L);
        fixture.settings().put("room_host_revision", 2L);

        assertThatThrownBy(() -> fixture.service().claim("guest"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("HOST_ALREADY_CLAIMED");
        assertThat(fixture.settings()).containsEntry("room_host_user_id", 7L)
                .containsEntry("room_host_revision", 2L);

        assertThat(fixture.service().claim("host")).containsEntry("revision", 3L);
        fixture.settings().put("room_host_user_id", 99L);
        assertThat(fixture.service().claim("guest"))
                .containsEntry("hostUserId", 8L)
                .containsEntry("revision", 4L);
    }

    @Test
    void releaseByAnotherUserFailsWithoutChangingTheClaimOrRevision() {
        Fixture fixture = fixture();
        register(fixture, "guest", 8L, "小红");
        fixture.settings().put("room_host_user_id", 7L);
        fixture.settings().put("room_host_revision", 4L);

        assertThatThrownBy(() -> fixture.service().release("guest"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("HOST_REQUIRED");

        assertThat(fixture.settings()).containsEntry("room_host_user_id", 7L)
                .containsEntry("room_host_revision", 4L);
        verify(fixture.users()).lockRoomHost();

        fixture.settings().remove("room_host_user_id");
        assertThatThrownBy(() -> fixture.service().release("guest"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("HOST_REQUIRED");
        assertThat(fixture.settings()).containsEntry("room_host_revision", 4L);
    }

    @Test
    void requireHostRejectsBlankAndUnknownClientTokens() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().requireHost(null))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("USER_REQUIRED");
        assertThatThrownBy(() -> fixture.service().requireHost("  "))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("USER_REQUIRED");
        assertThatThrownBy(() -> fixture.service().requireHost("unknown"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo("USER_REQUIRED");
    }

    @Test
    void statusParsesStringIdsAndRevisionsAndRejectsMalformedValues() {
        Fixture fixture = fixture();
        register(fixture, "host", 7L, "小明");
        register(fixture, "guest", 8L, "小红");
        fixture.settings().put("room_host_user_id", "7");
        fixture.settings().put("room_host_revision", "12");

        assertThat(fixture.service().status("host"))
                .containsEntry("claimed", true)
                .containsEntry("hostUserId", 7L)
                .containsEntry("hostNickname", "小明")
                .containsEntry("revision", 12L)
                .containsEntry("isHost", true);
        assertThat(fixture.service().status("guest")).containsEntry("claimed", true)
                .containsEntry("hostUserId", 7L)
                .containsEntry("isHost", false);

        fixture.settings().put("room_host_user_id", "not-a-number");
        fixture.settings().put("room_host_revision", "not-a-number");
        assertThat(fixture.service().status("host"))
                .containsEntry("claimed", false)
                .containsEntry("hostUserId", null)
                .containsEntry("revision", 0L)
                .containsEntry("isHost", false);

        fixture.settings().remove("room_host_user_id");
        fixture.settings().remove("room_host_revision");
        assertThat(fixture.service().status("host"))
                .containsEntry("claimed", false)
                .containsEntry("hostUserId", null)
                .containsEntry("revision", 0L)
                .containsEntry("isHost", false);
    }

    private static Fixture fixture() {
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, AppUser> usersByToken = new LinkedHashMap<>();
        Map<Long, AppUser> usersById = new LinkedHashMap<>();
        SettingService settingService = mock(SettingService.class);
        doAnswer(invocation -> {
            settings.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settingService).putInternal(anyString(), any());
        when(settingService.getAll()).thenReturn(settings);

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findByClientToken(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(usersByToken.get(invocation.getArgument(0))));
        when(users.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(usersById.get(invocation.getArgument(0))));
        when(users.existsById(anyLong())).thenAnswer(invocation -> usersById.containsKey(invocation.getArgument(0)));
        return new Fixture(new RoomHostService(settingService, users), users, settings, usersByToken, usersById);
    }

    private static AppUser register(Fixture fixture, String token, long id, String nickname) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setClientToken(token);
        user.setNickname(nickname);
        fixture.usersByToken().put(token, user);
        fixture.usersById().put(id, user);
        return user;
    }

    private record Fixture(RoomHostService service, AppUserRepository users, Map<String, Object> settings,
                           Map<String, AppUser> usersByToken, Map<Long, AppUser> usersById) {}
}
