package com.homektv.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.homektv.library.LibraryScanStateStore.ACTIVE_LIBRARY_KEY;

/** Transactional revision source for all committed catalog mutations. */
@Service
public class CatalogRevisionService {
    private final JdbcTemplate jdbc;

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
        return revision == null ? 0L : revision;
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

