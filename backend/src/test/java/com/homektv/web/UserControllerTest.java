package com.homektv.web;

import com.homektv.domain.AppUser;
import com.homektv.queue.UserService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    void rejectsMissingOrBlankClientTokensBeforeCallingTheService() {
        UserService service = mock(UserService.class);
        UserController controller = new UserController(service);
        Map<String, String> missing = new HashMap<>();
        missing.put("nickname", "客人");

        assertThatThrownBy(() -> controller.register(missing))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_ARGUMENT"));
        assertThatThrownBy(() -> controller.register(Map.of("client_token", "  ", "nickname", "客人")))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_ARGUMENT"));

        verifyNoInteractions(service);
    }

    @Test
    void returnsTheServiceResolvedUserIdAndNickname() {
        UserService service = mock(UserService.class);
        UserController controller = new UserController(service);
        AppUser user = new AppUser();
        user.setId(28L);
        user.setNickname("客人#2");
        when(service.upsert("client-1", "客人")).thenReturn(user);

        Map<String, Object> response = controller.register(Map.of("client_token", "client-1", "nickname", "客人"));

        assertThat(response).containsEntry("id", 28L).containsEntry("nickname", "客人#2");
        verify(service).upsert("client-1", "客人");
    }
}
