package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.repo.AppUserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceTest {

    @Test
    void upsertCountsNicknameConflictsInTheDatabaseWithoutLoadingAllUsers() {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUser user = new AppUser();
        user.setId(1L);
        user.setClientToken("current");
        user.setNickname("旧昵称");
        when(repository.insertIfAbsent("current", "家人1234")).thenReturn(0);
        when(repository.findByClientToken("current")).thenReturn(java.util.Optional.of(user));
        when(repository.countByClientTokenNotAndNickname("current", "小明")).thenReturn(0L);
        when(repository.countByClientTokenNotAndNicknameStartingWith("current", "小明#"))
                .thenReturn(1L);
        when(repository.save(user)).thenReturn(user);

        AppUser result = new UserService(repository).upsert("current", "小明");

        assertThat(result.getNickname()).isEqualTo("小明#2");
        verify(repository).countByClientTokenNotAndNickname(eq("current"), eq("小明"));
        verify(repository).countByClientTokenNotAndNicknameStartingWith(eq("current"), eq("小明#"));
        verify(repository, never()).findAll();
    }

    @Test
    void rejectsOversizedClientTokensAndNicknamesBeforePersisting() {
        AppUserRepository repository = mock(AppUserRepository.class);
        UserService service = new UserService(repository);

        assertThatThrownBy(() -> service.upsert("x".repeat(257), "小明"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsert("token", "x".repeat(41)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insertIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
