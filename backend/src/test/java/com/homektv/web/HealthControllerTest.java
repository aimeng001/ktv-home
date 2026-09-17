package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void healthReturnsUp() throws Exception {
        var identity = mock(com.homektv.discovery.ServerInstanceIdentityService.class);
        when(identity.cachedOrDefault()).thenReturn("550e8400-e29b-41d4-a716-446655440000");
        MockMvc mockMvc = standaloneSetup(new HealthController(identity)).build();
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.instanceId").value("550e8400-e29b-41d4-a716-446655440000"));
    }

    /**
     * /api/health 是轻量服务发现端点，必须完全不依赖数据库；数据库就绪由 /api/ready 承担。
     * A failing database must not make health discovery fail or block on a query.
     */
    @Test
    void healthNeverResolvesTheIdentityFromTheDatabase() throws Exception {
        var identity = mock(com.homektv.discovery.ServerInstanceIdentityService.class);
        when(identity.cachedOrDefault()).thenReturn("550e8400-e29b-41d4-a716-446655440000");
        when(identity.getOrCreate()).thenThrow(new IllegalStateException("database is unavailable"));

        MockMvc mockMvc = standaloneSetup(new HealthController(identity)).build();
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("home-ktv"))
                .andExpect(jsonPath("$.instanceId").value("550e8400-e29b-41d4-a716-446655440000"));

        verify(identity, never()).getOrCreate();
    }
}
