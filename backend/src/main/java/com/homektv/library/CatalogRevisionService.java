package com.homektv.library;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.homektv.library.LibraryScanStateStore.ACTIVE_LIBRARY_KEY;

/** Transactional revision source for all committed catalog mutations. */
@Service
public class CatalogRevisionService {
    private final JdbcTemplate jdbc;
    private LibraryCatalogStatsService catalogStats;
    private ArtistDirectoryProjectionService directoryProjection;

    @Autowired(required = false)
    void setDirectoryProjection(ArtistDirectoryProjectionService directoryProjection) {
        this.directoryProjection = directoryProjection;
    }

    @Autowired(required = false)
    void setCatalogStats(LibraryCatalogStatsService catalogStats) {
        this.catalogStats = catalogStats;
    }

    public CatalogRevisionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long bumpIfChanged(boolean changed) {
        ensureRow();
        if (!changed) return current();
        Long revision = jdbc.queryForObject("""
                UPDATE library_catalog_revision
                   SET revision = revision + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE library_key = ?
                 RETURNING revision
                """, Long.class, ACTIVE_LIBRARY_KEY);
        long nextRevision = revision == null ? 0L : revision;
        if (catalogStats != null || directoryProjection != null) {
            Runnable refreshes = () -> {
                if (catalogStats != null) catalogStats.requestRefresh();
                if (directoryProjection != null) directoryProjection.requestRefresh();
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { refreshes.run(); }
                });
            } else {
                refreshes.run();
            }
        }
        return nextRevision;
    }

    @Transactional(readOnly = true)
    public long current() {
        Long revision = jdbc.query("""
                SELECT revision
                  FROM library_catalog_revision
                 WHERE library_key = ?
                """,
                ps -> ps.setString(1, ACTIVE_LIBRARY_KEY),
                rs -> rs.next() ? rs.getLong(1) : 0L);
        return revision == null ? 0L : revision;
    }
    private void ensureRow() {
        jdbc.update("""
                INSERT INTO library_catalog_revision(library_key, revision)
                VALUES (?, 0)
                ON CONFLICT (library_key) DO NOTHING
                """, ACTIVE_LIBRARY_KEY);
    }
}

