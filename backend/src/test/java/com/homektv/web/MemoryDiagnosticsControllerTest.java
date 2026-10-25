package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.diagnostics.MemoryDiagnostics;
import com.homektv.diagnostics.MemoryDiagnosticsService;
import com.homektv.resource.ResourceBudget;
import com.homektv.security.AdminAuthInterceptor;
import com.homektv.security.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MemoryDiagnosticsControllerTest {

    @Test
    void memoryDiagnosticsReusesAdminAuthentication() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setAdminPassword("admin-secret");
        AdminAuthService auth = new AdminAuthService(properties);
        String token = auth.login("admin-secret");
        MemoryDiagnosticsService service = mock(MemoryDiagnosticsService.class);
        when(service.snapshot()).thenReturn(new MemoryDiagnostics(
                128, 768, 2048, 128, true, "MEDIA_PROBE", 48_122, 17_500,
                new ResourceBudget.Snapshot(
                        new ResourceBudget.WaitSnapshot(1, 2, 3, 7, 8_000, 5_000),
                        1, 4,
                        new ResourceBudget.WaitSnapshot(0, 1, 1, 9, 10_000, 6_000))));
        MockMvc mvc = standaloneSetup(new MemoryDiagnosticsController(service))
                .addInterceptors(new AdminAuthInterceptor(auth))
                .build();

        mvc.perform(get("/api/admin/diagnostics/memory"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/diagnostics/memory")
                        .header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heapUsedBytes").value(128))
                .andExpect(jsonPath("$.heapMaxBytes").value(2048))
                .andExpect(jsonPath("$.scanRunning").value(true))
                .andExpect(jsonPath("$.scanPhase").value("MEDIA_PROBE"))
                .andExpect(jsonPath("$.discoveredFiles").value(48_122))
                .andExpect(jsonPath("$.probeProcessed").value(17_500))
                .andExpect(jsonPath("$.resourceBudget.probes.active").value(1))
                .andExpect(jsonPath("$.resourceBudget.probes.waiting").value(2))
                .andExpect(jsonPath("$.resourceBudget.probes.waitCount").value(7))
                .andExpect(jsonPath("$.resourceBudget.probes.totalWaitNanos").value(8_000))
                .andExpect(jsonPath("$.resourceBudget.activeLiveTranscodes").value(1))
                .andExpect(jsonPath("$.resourceBudget.liveTranscodeLimit").value(4))
                .andExpect(jsonPath("$.resourceBudget.managedTranscodes.waiting").value(1))
                .andExpect(jsonPath("$.resourceBudget.managedTranscodes.maxWaitNanos").value(6_000));
    }
}
