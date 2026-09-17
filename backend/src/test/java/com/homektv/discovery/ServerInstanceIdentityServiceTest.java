package com.homektv.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerInstanceIdentityServiceTest {
    @Test
    void identityIsCreatedOnceAndThenReadFromThePersistentSetting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String persisted = UUID.randomUUID().toString();
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(ServerInstanceIdentityService.SETTING_KEY)))
                .thenThrow(new EmptyResultDataAccessException(1))
                .thenReturn(persisted);
        when(jdbc.update(anyString(), eq(ServerInstanceIdentityService.SETTING_KEY), anyString()))
                .thenReturn(1);

        ServerInstanceIdentityService service = new ServerInstanceIdentityService(jdbc);

        String first = service.getOrCreate();
        String second = service.getOrCreate();

        assertThat(first).isEqualTo(persisted);
        assertThat(second).isEqualTo(persisted);
        verify(jdbc).update(anyString(), eq(ServerInstanceIdentityService.SETTING_KEY), anyString());
    }

    @Test
    void resolvingTheIdentityAlsoWarmsTheHealthCache() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String persisted = UUID.randomUUID().toString();
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(ServerInstanceIdentityService.SETTING_KEY)))
                .thenReturn(persisted);

        ServerInstanceIdentityService service = new ServerInstanceIdentityService(jdbc);

        assertThat(service.getOrCreate()).isEqualTo(persisted);
        // mDNS 广播同一身份；若 /api/health 此时仍返回 unknown，客户端会判定身份不一致。
        assertThat(service.cachedOrDefault()).isEqualTo(persisted);
    }

    @Test
    void cachedIdentityIsUnknownUntilAValueIsCached() {
        ServerInstanceIdentityService service = new ServerInstanceIdentityService(mock(JdbcTemplate.class));

        assertThat(service.cachedOrDefault()).isEqualTo("unknown");
    }

    @Test
    void cachedIdentityIsExposedOnceCached() {
        ServerInstanceIdentityService service = new ServerInstanceIdentityService(mock(JdbcTemplate.class));
        String instanceId = UUID.randomUUID().toString();

        service.cache(instanceId);

        assertThat(service.cachedOrDefault()).isEqualTo(instanceId);
    }

    @Test
    void cachedIdentityIgnoresValuesThatAreNotUuids() {
        ServerInstanceIdentityService service = new ServerInstanceIdentityService(mock(JdbcTemplate.class));

        service.cache("not-an-instance-id");
        service.cache(null);

        assertThat(service.cachedOrDefault()).isEqualTo("unknown");
    }
}
