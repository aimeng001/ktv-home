package com.homektv.web;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadinessControllerTest {

    @Test
    void readinessReturnsOkWhenDatabaseIsReachable() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var identity = mock(com.homektv.discovery.ServerInstanceIdentityService.class);
        when(identity.getOrCreate()).thenReturn("550e8400-e29b-41d4-a716-446655440000");

        MockMvc mvc = standaloneSetup(new ReadinessController(jdbc, identity)).build();

        mvc.perform(get("/api/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("home-ktv"))
                .andExpect(jsonPath("$.instanceId").value("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void readinessReturnsServiceUnavailableWhenDatabaseCannotBeReached() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var identity = mock(com.homektv.discovery.ServerInstanceIdentityService.class);
        when(identity.getOrCreate()).thenReturn("550e8400-e29b-41d4-a716-446655440000");

        MockMvc mvc = standaloneSetup(new ReadinessController(jdbc, identity)).build();

        mvc.perform(get("/api/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.service").value("home-ktv"))
                .andExpect(jsonPath("$.instanceId").value("550e8400-e29b-41d4-a716-446655440000"));
    }
}
