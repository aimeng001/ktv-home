package com.homektv.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Database-backed readiness probe for Docker and deployment smoke checks. */
@RestController
@RequestMapping("/api")
public class ReadinessController {
    private final JdbcTemplate jdbc;

    public ReadinessController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "service", "home-ktv"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503)
                    .body(Map.of("status", "DOWN", "service", "home-ktv"));
        }
    }
}
