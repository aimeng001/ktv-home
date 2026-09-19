package com.homektv.library;

import com.homektv.config.AppProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Persistent lease and last-result state for the active external library scan. */
@Component
public class LibraryScanStateStore {
    public static final String ACTIVE_LIBRARY_KEY = "active";
    public static final int METADATA_POLICY_VERSION = 1;
    private static final int LEASE_SECONDS = 300;

    private final JdbcTemplate jdbc;

    public LibraryScanStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean needsBootstrap(AppProperties props) {
        Snapshot current = find().orElse(null);
        if (current == null) return true;
        String root = normalizedRoot(props);
        String mode = props.getLibraryMode().name();
        // A changed root is deliberately not auto-merged into the active
        // catalogue. The operator must review the deployment identity first.
        if (!root.equals(current.configuredRoot()) || !mode.equals(current.libraryMode())) return false;
        return current.metadataPolicyVersion() != METADATA_POLICY_VERSION
                || current.state() != State.COMPLETED;
    }

    public Optional<Claim> tryClaim(AppProperties props, String trigger) {
        String root = normalizedRoot(props);
        String mode = props.getLibraryMode().name();
        UUID owner = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO library_scan_state(library_key, configured_root, library_mode,
                    metadata_policy_version, state, phase, updated_at)
                VALUES (?, ?, ?, ?, 'IDLE', 'IDLE', CURRENT_TIMESTAMP)
                ON CONFLICT (library_key) DO NOTHING
                """, ACTIVE_LIBRARY_KEY, root, mode, METADATA_POLICY_VERSION);
        return jdbc.query("""
                    UPDATE library_scan_state
                    SET owner_id = ?, scan_id = ?, generation = generation + 1,
                        state = 'RUNNING', phase = ?, heartbeat_at = CURRENT_TIMESTAMP,
                        started_at = CURRENT_TIMESTAMP, finished_at = NULL,
                        discovered_files = 0, indexed_files = 0,
                        probe_completed_files = 0, probe_pending_files = 0,
                        error_code = NULL, error_message = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE library_key = ? AND configured_root = ? AND library_mode = ?
                      AND metadata_policy_version = ?
                      AND (state <> 'RUNNING' OR heartbeat_at IS NULL
                           OR heartbeat_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                    RETURNING scan_id, owner_id, generation
                    """, ps -> {
                        ps.setObject(1, owner);
                        ps.setObject(2, scanId);
                        ps.setString(3, trigger);
                        ps.setString(4, ACTIVE_LIBRARY_KEY);
                        ps.setString(5, root);
                        ps.setString(6, mode);
                        ps.setInt(7, METADATA_POLICY_VERSION);
                        ps.setInt(8, LEASE_SECONDS);
                    }, (rs, row) -> new Claim(
                            rs.getObject("scan_id", UUID.class),
                            rs.getObject("owner_id", UUID.class),
                    rs.getLong("generation"))).stream().findFirst();
    }

    public void heartbeat(Claim claim, ScanSnapshot progress) {
        jdbc.update("""
                UPDATE library_scan_state
                SET phase = ?, discovered_files = ?, indexed_files = ?,
                    probe_completed_files = ?, probe_pending_files = ?,
                    heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE library_key = ? AND owner_id = ? AND scan_id = ? AND generation = ?
                """, progress.phase(), progress.discoveredFiles(), progress.indexedFiles(),
                progress.probeCompletedFiles(), progress.probePendingFiles(),
                ACTIVE_LIBRARY_KEY, claim.ownerId(), claim.scanId(), claim.generation());
    }

    public void markCompleted(Claim claim, LibraryScanService.ScanResult result,
                              LibraryScanService.ScanProgress progress) {
        ScanSnapshot snapshot = ScanSnapshot.from(result, progress);
        jdbc.update("""
                UPDATE library_scan_state
                SET state = ?, phase = ?, discovered_files = ?, indexed_files = ?,
                    probe_completed_files = ?, probe_pending_files = ?,
                    finished_at = CURRENT_TIMESTAMP, last_successful_scan_at = CURRENT_TIMESTAMP,
                    heartbeat_at = NULL, error_code = NULL, error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE library_key = ? AND owner_id = ? AND scan_id = ? AND generation = ?
                """, progress == null || progress.state() == LibraryScanService.ScanState.COMPLETED
                        ? State.COMPLETED.name() : progress.state().name(),
                snapshot.phase(), snapshot.discoveredFiles(), snapshot.indexedFiles(),
                snapshot.probeCompletedFiles(), snapshot.probePendingFiles(),
                ACTIVE_LIBRARY_KEY, claim.ownerId(), claim.scanId(), claim.generation());
    }

    public void markFailed(Claim claim, String code, String message) {
        jdbc.update("""
                UPDATE library_scan_state
                SET state = 'FAILED', phase = 'FAILED', finished_at = CURRENT_TIMESTAMP,
                    heartbeat_at = NULL, error_code = ?, error_message = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE library_key = ? AND owner_id = ? AND scan_id = ? AND generation = ?
                """, safe(code, "SCAN_RUNTIME_ERROR"), safe(message, "扫描失败"),
                ACTIVE_LIBRARY_KEY, claim.ownerId(), claim.scanId(), claim.generation());
    }

    public Optional<Snapshot> find() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT library_key, configured_root, library_mode, metadata_policy_version,
                           state, phase, scan_id, owner_id, generation,
                           discovered_files, indexed_files, probe_completed_files,
                           probe_pending_files, error_code, error_message, updated_at
                    FROM library_scan_state WHERE library_key = ?
                    """, (rs, row) -> new Snapshot(
                    rs.getString("library_key"), rs.getString("configured_root"),
                    rs.getString("library_mode"), rs.getInt("metadata_policy_version"),
                    State.valueOf(rs.getString("state")), rs.getString("phase"),
                    rs.getObject("scan_id", UUID.class), rs.getObject("owner_id", UUID.class),
                    rs.getLong("generation"), rs.getLong("discovered_files"),
                    rs.getLong("indexed_files"), rs.getLong("probe_completed_files"),
                    rs.getLong("probe_pending_files"), rs.getString("error_code"),
                    rs.getString("error_message"), rs.getObject("updated_at", OffsetDateTime.class)),
                    ACTIVE_LIBRARY_KEY));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    private static String normalizedRoot(AppProperties props) {
        return Path.of(LibraryModePolicy.activeLibraryRoot(props).toString())
                .toAbsolutePath().normalize().toString();
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record Claim(UUID scanId, UUID ownerId, long generation) {}

    public record Snapshot(String libraryKey, String configuredRoot, String libraryMode,
                           int metadataPolicyVersion, State state, String phase,
                           UUID scanId, UUID ownerId, long generation,
                           long discoveredFiles, long indexedFiles, long probeCompletedFiles,
                           long probePendingFiles, String errorCode, String errorMessage,
                           OffsetDateTime updatedAt) {}

    public enum State { IDLE, RUNNING, COMPLETED, PARTIAL, FAILED, INTERRUPTED }

    public record ScanSnapshot(String phase, long discoveredFiles, long indexedFiles,
                               long probeCompletedFiles, long probePendingFiles) {
        static ScanSnapshot from(LibraryScanService.ScanResult result,
                                  LibraryScanService.ScanProgress progress) {
            return new ScanSnapshot(progress == null ? "COMPLETED" : progress.phase(),
                    result == null ? 0 : result.scanned(),
                    result == null ? 0 : result.fastIndexed(),
                    result == null ? 0 : result.probeCalls(),
                    result == null ? 0 : result.probeQueued());
        }
    }
}
