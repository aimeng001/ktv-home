package com.homektv.web;

import com.homektv.discovery.ServerInstanceIdentityService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Database-backed readiness probe for Docker and deployment smoke checks. */
@RestController
@RequestMapping("/api")
public class ReadinessController {
    private final JdbcTemplate jdbc;
    private final ServerInstanceIdentityService identityService;

    public ReadinessController(JdbcTemplate jdbc, ServerInstanceIdentityService identityService) {
        this.jdbc = jdbc;
        this.identityService = identityService;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        String instanceId = null;
        try {
            instanceId = identityService.getOrCreate();
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "service", "home-ktv",
                    "instanceId", instanceId));
        } catch (DataAccessException | IllegalStateException failure) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("status", "DOWN");
            body.put("service", "home-ktv");
            if (instanceId != null && !instanceId.isBlank()) body.put("instanceId", instanceId);
            return ResponseEntity.status(503)
                    .body(body);
        }
    }
}
