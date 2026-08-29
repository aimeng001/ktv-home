package com.homektv.web;

import com.homektv.system.BuildInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BuildInfoControllerTest {

    @Test
    void exposesBuildIdentityWithoutAdminAuthentication() throws Exception {
        BuildInfoService service = mock(BuildInfoService.class);
        when(service.get()).thenReturn(new BuildInfoService.BuildInfo(
                "1.0.11", "0123456789abcdef0123456789abcdef01234567",
                "2026-08-29T12:00:00Z", 25));
        MockMvc mvc = standaloneSetup(new BuildInfoController(service)).build();

        mvc.perform(get("/api/build-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.11"))
                .andExpect(jsonPath("$.gitSha").value("0123456789abcdef0123456789abcdef01234567"))
                .andExpect(jsonPath("$.buildTime").value("2026-08-29T12:00:00Z"))
                .andExpect(jsonPath("$.latestMigration").value(25));
    }
}
