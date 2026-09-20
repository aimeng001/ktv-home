package com.homektv.library;

import com.homektv.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persists the small counter set exposed by the public library status endpoint. */
@Service
public class LibraryCatalogStatsService {
    private static final Logger log = LoggerFactory.getLogger(LibraryCatalogStatsService.class);
    static final String ACTIVE_LIBRARY_KEY = LibraryScanStateStore.ACTIVE_LIBRARY_KEY;

    static final String REFRESH_SQL = """
            INSERT INTO library_catalog_stats(
                library_key, total_songs, indexed_songs, ready_songs,
                probe_pending_files, refreshed_at)
            SELECT ?,
                   (SELECT COUNT(*) FROM songs),
                   (SELECT COUNT(DISTINCT s.id)
                      FROM songs s JOIN song_files f ON f.song_id = s.id
                     WHERE s.status = 'ok' AND f.file_role = ? AND f.valid = TRUE),
                   (SELECT COUNT(DISTINCT s.id)
                      FROM songs s JOIN song_files f ON f.song_id = s.id
                     WHERE s.status = 'ok' AND f.file_role = ? AND f.valid = TRUE
                       AND f.probe_pending = FALSE
                       AND f.media_type IS NOT NULL
                       AND TRIM(f.media_type) <> ''
                       AND LOWER(TRIM(f.media_type)) <> 'pending_probe'),
                   (SELECT COUNT(*) FROM song_files f
                     WHERE f.file_role = ? AND f.probe_pending = TRUE),
                   CURRENT_TIMESTAMP
            ON CONFLICT (library_key) DO UPDATE SET
                total_songs = EXCLUDED.total_songs,
                indexed_songs = EXCLUDED.indexed_songs,
                ready_songs = EXCLUDED.ready_songs,
                probe_pending_files = EXCLUDED.probe_pending_files,
                refreshed_at = EXCLUDED.refreshed_at
            """;

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "library-catalog-stats-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();

    public LibraryCatalogStatsService(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Coalesces scan/admin revisions so one request wave performs one counter pass. */
    public void requestRefresh() {
        refreshRequested.set(true);
        if (!refreshRunning.compareAndSet(false, true)) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshRequested.set(false);
                    refresh();
                } catch (RuntimeException failure) {
                    log.warn("library catalog counters refresh failed: {}", failure.getMessage());
                } finally {
                    refreshRunning.set(false);
                    if (refreshRequested.get()) requestRefresh();
                }
            });
        } catch (RejectedExecutionException failure) {
            refreshRunning.set(false);
            log.debug("library catalog counters refresh rejected: {}", failure.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void refreshAtStartup() {
        requestRefresh();
    }

    public void refresh() {
        String role = LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
        jdbc.update(REFRESH_SQL, ACTIVE_LIBRARY_KEY, role, role, role);
    }

    public Optional<Stats> find() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT total_songs, indexed_songs, ready_songs, probe_pending_files, refreshed_at
                    FROM library_catalog_stats WHERE library_key = ?
                    """, (rs, row) -> new Stats(
                    rs.getLong("total_songs"), rs.getLong("indexed_songs"),
                    rs.getLong("ready_songs"), rs.getLong("probe_pending_files"),
                    rs.getObject("refreshed_at", OffsetDateTime.class)), ACTIVE_LIBRARY_KEY));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }

    public record Stats(long totalSongs, long indexedSongs, long readySongs,
                        long probePendingFiles, OffsetDateTime refreshedAt) {}
}
