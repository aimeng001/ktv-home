package com.homektv.web;

import com.homektv.security.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminAuthControllerTest {

    @Test
    void passesTheConnectionAddressToTheLoginRateLimiter() throws Exception {
        AdminAuthService auth = mock(AdminAuthService.class);
        when(auth.login("password", "192.168.1.30")).thenReturn("session-token");
        when(auth.sessionLifetimeSeconds()).thenReturn(43_200L);
        MockMvc mvc = standaloneSetup(new AdminAuthController(auth)).build();

        mvc.perform(post("/api/admin/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.30");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("session-token"));

        verify(auth).login("password", "192.168.1.30");
    }
}
