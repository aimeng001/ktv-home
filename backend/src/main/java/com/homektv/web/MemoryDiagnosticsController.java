package com.homektv.web;

import com.homektv.diagnostics.MemoryDiagnostics;
import com.homektv.diagnostics.MemoryDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only memory and scan diagnostics; authentication is registered centrally. */
@RestController
@RequestMapping("/api/admin/diagnostics/memory")
public class MemoryDiagnosticsController {
    private final MemoryDiagnosticsService service;

    public MemoryDiagnosticsController(MemoryDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping
    public MemoryDiagnostics diagnostics() {
        return service.snapshot();
    }
}
