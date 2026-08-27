package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.diagnostics.NasMountInspector;
import com.homektv.diagnostics.DiagnosticBundleService;
import com.homektv.ws.ActivePlayerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal admin-only operational diagnostics with an explicit response allowlist. */
@RestController
@RequestMapping("/api/admin/diagnostics")
public class DiagnosticsController {

    private final AppProperties properties;
    private final NasMountInspector mountInspector;
    private final ActivePlayerRegistry players;
    private final DiagnosticBundleService bundleService;

    public DiagnosticsController(AppProperties properties, NasMountInspector mountInspector,
                                 ActivePlayerRegistry players, DiagnosticBundleService bundleService) {
        this.properties = properties;
        this.mountInspector = mountInspector;
        this.players = players;
        this.bundleService = bundleService;
    }

    @PostMapping(value = "/bundle", produces = "application/zip")
    public ResponseEntity<byte[]> bundle() throws IOException {
        DiagnosticsResponse response = diagnostics();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("service", response.service());
        summary.put("version", response.version());
        summary.put("libraryMode", response.libraryMode());
        summary.put("nasMountStatus", response.nasMountStatus());
        summary.put("activePlayerConnected", response.activePlayerConnected());
        summary.put("connectedPlayers", response.connectedPlayers());
        byte[] zip = bundleService.create(Path.of(properties.getDataPath(), "logs"), summary);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=home-ktv-diagnostics.zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zip.length)
                .body(zip);
    }

    @GetMapping
    public DiagnosticsResponse diagnostics() {
        return new DiagnosticsResponse(
                "home-ktv",
                properties.getRelease().getVersion(),
                properties.getLibraryMode().name(),
                mountInspector.inspectConfiguredLibrary().status().name(),
                players.activeSessionId().isPresent(),
                players.connectedPlayerCount());
    }

    public record DiagnosticsResponse(
            String service,
            String version,
            String libraryMode,
            String nasMountStatus,
            boolean activePlayerConnected,
            int connectedPlayers) {}
}
