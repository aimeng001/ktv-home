package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.common.BackgroundJobStatusProvider;
import com.homektv.common.BackgroundJobSummary;
import com.homektv.diagnostics.DiagnosticsMetricsRecorder;
import com.homektv.diagnostics.MemoryDiagnostics;
import com.homektv.diagnostics.MemoryDiagnosticsService;
import com.homektv.diagnostics.NasMountInspector;
import com.homektv.diagnostics.DiagnosticBundleService;
import com.homektv.resource.PlaybackPressureMonitor;
import com.homektv.ws.ActivePlayerRegistry;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal admin-only operational diagnostics with an explicit response allowlist. */
@RestController
@RequestMapping("/api/admin/diagnostics")
public class DiagnosticsController {

    private final AppProperties properties;
    private final NasMountInspector mountInspector;
    private final ActivePlayerRegistry players;
    private final DiagnosticBundleService bundleService;
    private MemoryDiagnosticsService memoryDiagnosticsService;
    private DataSource dataSource;
    private PlaybackPressureMonitor playbackPressureMonitor;
    private DiagnosticsMetricsRecorder metricsRecorder;
    private List<BackgroundJobStatusProvider> jobStatusProviders;

    public DiagnosticsController(AppProperties properties, NasMountInspector mountInspector,
                                 ActivePlayerRegistry players, DiagnosticBundleService bundleService) {
        this.properties = properties;
        this.mountInspector = mountInspector;
        this.players = players;
        this.bundleService = bundleService;
    }

    @Autowired(required = false)
    void setJobStatusProviders(List<BackgroundJobStatusProvider> jobStatusProviders) {
        this.jobStatusProviders = jobStatusProviders;
    }

    @Autowired(required = false)
    void setMemoryDiagnosticsService(MemoryDiagnosticsService memoryDiagnosticsService) {
        this.memoryDiagnosticsService = memoryDiagnosticsService;
    }

    @Autowired(required = false)
    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Autowired(required = false)
    void setPlaybackPressureMonitor(PlaybackPressureMonitor playbackPressureMonitor) {
        this.playbackPressureMonitor = playbackPressureMonitor;
    }

    @Autowired(required = false)
    void setMetricsRecorder(DiagnosticsMetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
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
        summary.put("playbackBusy", response.playbackBusy());
        if (response.memory() != null) summary.put("memory", response.memory());
        if (response.hikariPool() != null) summary.put("hikariPool", response.hikariPool());
        if (response.counters() != null) summary.put("counters", response.counters());
        if (response.backgroundJobs() != null) summary.put("backgroundJobs", response.backgroundJobs());
        byte[] zip = bundleService.create(Path.of(properties.getDataPath(), "logs"), summary);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=home-ktv-diagnostics.zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zip.length)
                .body(zip);
    }

    @GetMapping
    public DiagnosticsResponse diagnostics() {
        MemoryDiagnostics memory = memoryDiagnosticsService != null ? memoryDiagnosticsService.snapshot() : null;
        HikariPoolSnapshot hikariPool = inspectHikariPool();
        Boolean playbackBusy = playbackPressureMonitor != null ? playbackPressureMonitor.isPlaybackBusy() : null;
        Map<String, Long> counters = metricsRecorder != null ? metricsRecorder.snapshot() : null;
        List<BackgroundJobSummary> backgroundJobs = jobStatusProviders == null ? null : jobStatusProviders.stream()
                .map(BackgroundJobStatusProvider::jobStatus)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(BackgroundJobSummary::jobKey))
                .toList();
        return new DiagnosticsResponse(
                "home-ktv",
                properties.getRelease().getVersion(),
                properties.getLibraryMode().name(),
                mountInspector.inspectConfiguredLibrary().status().name(),
                players.activeSessionId().isPresent(),
                players.connectedPlayerCount(),
                playbackBusy,
                memory,
                hikariPool,
                counters,
                backgroundJobs);
    }

    private HikariPoolSnapshot inspectHikariPool() {
        if (!(dataSource instanceof HikariDataSource hikari)) return null;
        HikariPoolMXBean bean = hikari.getHikariPoolMXBean();
        if (bean == null) return null;
        return new HikariPoolSnapshot(
                bean.getActiveConnections(),
                bean.getIdleConnections(),
                bean.getThreadsAwaitingConnection(),
                bean.getTotalConnections(),
                hikari.getMaximumPoolSize());
    }

    public record HikariPoolSnapshot(
            int activeConnections,
            int idleConnections,
            int threadsAwaitingConnection,
            int totalConnections,
            int maximumPoolSize) {}

    public record DiagnosticsResponse(
            String service,
            String version,
            String libraryMode,
            String nasMountStatus,
            boolean activePlayerConnected,
            int connectedPlayers,
            Boolean playbackBusy,
            MemoryDiagnostics memory,
            HikariPoolSnapshot hikariPool,
            Map<String, Long> counters,
            List<BackgroundJobSummary> backgroundJobs) {}
}
