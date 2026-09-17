package com.homektv.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerInstanceIdentityWarmUpTest {

    @Test
    void warmUpCachesTheIdentityResolvedAtStartup() {
        ServerInstanceIdentityService identity = mock(ServerInstanceIdentityService.class);
        String instanceId = "550e8400-e29b-41d4-a716-446655440000";
        when(identity.getOrCreate()).thenReturn(instanceId);

        new ServerInstanceIdentityWarmUp(identity).run(mock(ApplicationArguments.class));

        verify(identity).cache(instanceId);
    }

    /**
     * 启动预热失败不得阻止应用启动；/api/ready 仍负责对外报告数据库未就绪。
     * A cold cache degrades /api/health to "unknown" instead of blocking startup.
     */
    @Test
    void warmUpFailureDoesNotAbortStartup() {
        ServerInstanceIdentityService identity = mock(ServerInstanceIdentityService.class);
        when(identity.getOrCreate()).thenThrow(new IllegalStateException("database is unavailable"));

        assertThatCode(() -> new ServerInstanceIdentityWarmUp(identity).run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
    }
}
