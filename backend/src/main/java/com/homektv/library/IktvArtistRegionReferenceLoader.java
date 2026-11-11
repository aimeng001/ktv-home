package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publishes the bundled exact-name singer region map independently from song metadata. */
@Component
public final class IktvArtistRegionReferenceLoader {
    private static final Logger log = LoggerFactory.getLogger(IktvArtistRegionReferenceLoader.class);
    private static final String DATA_RESOURCE = "classpath:iktv/iktv-singers.csv";
    private static final String MANIFEST_RESOURCE = "classpath:iktv/iktv-singers.manifest.properties";
    private static final String INSERT_REFERENCE_SQL = """
            INSERT INTO iktv_reference_artists (
                snapshot_version, artist_key, display_name, region, gender, source_category
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (snapshot_version, artist_key) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                region = EXCLUDED.region,
                gender = EXCLUDED.gender,
                source_category = EXCLUDED.source_category
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ResourceLoader resources;
    private final IktvArtistRegionReferenceReader reader;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public IktvArtistRegionReferenceLoader(JdbcTemplate jdbc,
                                           PlatformTransactionManager transactionManager,
                                           ResourceLoader resources,
                                           IktvArtistRegionReferenceReader reader,
                                           @Qualifier("iktvReferenceCatalogExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.resources = resources;
        this.reader = reader;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.iktv-reference.retry-delay-ms:300000}",
            initialDelayString = "${app.iktv-reference.initial-delay-ms:30000}")
    public void scheduleImport() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    importPackagedReferenceIfNeeded();
                } catch (Exception failure) {
                    log.error("IKTV artist region reference import failed; active snapshot remains available", failure);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.warn("IKTV artist region reference import was not scheduled: {}",
                    rejected.getClass().getSimpleName());
        }
    }

    void importPackagedReferenceIfNeeded() throws Exception {
        Properties manifest = readManifest();
        String expectedSha = requiredProperty(manifest, "source.sha256");
        long expectedRows = Long.parseLong(requiredProperty(manifest, "source.row-count"));
        Resource data = resources.getResource(DATA_RESOURCE);
        if (!data.exists()) throw new IllegalStateException("ARTIST_REFERENCE_RESOURCE_MISSING");

        IktvArtistRegionReferenceReader.Snapshot snapshot;
        try (InputStream input = data.getInputStream()) {
            snapshot = reader.read(input, expectedSha, expectedRows);
        }
        if (isActive(snapshot)) return;

        jdbc.update("""
                INSERT INTO iktv_artist_reference_snapshots (
                    snapshot_version, status, source_row_count, loaded_entry_count,
                    conflicting_key_count, source_sha256, last_error_code, updated_at
                ) VALUES (?, 'STAGING', ?, 0, ?, ?, NULL, now())
                ON CONFLICT (snapshot_version) DO UPDATE SET
                    status = 'STAGING', source_row_count = EXCLUDED.source_row_count,
                    loaded_entry_count = 0, conflicting_key_count = EXCLUDED.conflicting_key_count,
                    source_sha256 = EXCLUDED.source_sha256, last_error_code = NULL, updated_at = now()
                WHERE iktv_artist_reference_snapshots.status <> 'ACTIVE'
                """, snapshot.sha256(), snapshot.sourceRowCount(), snapshot.conflictingKeyCount(),
                snapshot.sha256());
        jdbc.update("DELETE FROM iktv_reference_artists WHERE snapshot_version = ?", snapshot.sha256());
        try {
            flush(snapshot.sha256(), snapshot.entries());
            int updated = jdbc.update("""
                    UPDATE iktv_artist_reference_snapshots
                       SET loaded_entry_count = ?, last_error_code = NULL, updated_at = now()
                     WHERE snapshot_version = ? AND status = 'STAGING'
                    """, snapshot.entries().size(), snapshot.sha256());
            if (updated != 1) throw new IllegalStateException("ARTIST_REFERENCE_STAGING_ROW_MISSING");

            transaction.executeWithoutResult(status -> {
                Long loaded = jdbc.queryForObject("""
                        SELECT loaded_entry_count FROM iktv_artist_reference_snapshots
                         WHERE snapshot_version = ? AND status = 'STAGING' FOR UPDATE
                        """, Long.class, snapshot.sha256());
                if (loaded == null || loaded != snapshot.entries().size()) {
                    throw new IllegalStateException("ARTIST_REFERENCE_LOADED_COUNT_MISMATCH");
                }
                jdbc.update("""
                        UPDATE iktv_artist_reference_snapshots
                           SET status = 'RETIRED', updated_at = now() WHERE status = 'ACTIVE'
                        """);
                int activated = jdbc.update("""
                        UPDATE iktv_artist_reference_snapshots
                           SET status = 'ACTIVE', activated_at = now(), updated_at = now()
                         WHERE snapshot_version = ? AND status = 'STAGING'
                        """, snapshot.sha256());
                if (activated != 1) throw new IllegalStateException("ARTIST_REFERENCE_ACTIVATION_FAILED");
            });
        } catch (Exception failure) {
            jdbc.update("""
                    UPDATE iktv_artist_reference_snapshots
                       SET status = 'FAILED', last_error_code = ?, updated_at = now()
                     WHERE snapshot_version = ? AND status = 'STAGING'
                    """, reasonCode(failure), snapshot.sha256());
            throw failure;
        }
        log.info("Validated IKTV singer region reference activated: version={}, rows={}, unique={}, conflicts={}",
                snapshot.sha256(), snapshot.sourceRowCount(), snapshot.entries().size(),
                snapshot.conflictingKeyCount());
    }

    private Properties readManifest() throws Exception {
        Resource manifestResource = resources.getResource(MANIFEST_RESOURCE);
        if (!manifestResource.exists()) throw new IllegalStateException("ARTIST_REFERENCE_MANIFEST_MISSING");
        Properties manifest = new Properties();
        try (InputStream input = manifestResource.getInputStream()) {
            manifest.load(input);
        }
        return manifest;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("ARTIST_REFERENCE_MANIFEST_INVALID");
        return value.trim();
    }

    private boolean isActive(IktvArtistRegionReferenceReader.Snapshot snapshot) {
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_artist_reference_snapshots
                 WHERE snapshot_version = ? AND status = 'ACTIVE'
                """, Integer.class, snapshot.sha256());
        if (active == null || active == 0) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_artist_reference_snapshots
                 WHERE snapshot_version = ? AND status = 'ACTIVE'
                   AND source_sha256 = ? AND source_row_count = ?
                   AND loaded_entry_count = ? AND conflicting_key_count = ?
                """, Integer.class, snapshot.sha256(), snapshot.sha256(), snapshot.sourceRowCount(),
                snapshot.entries().size(), snapshot.conflictingKeyCount());
        if (count != null && count == 1) return true;
        throw new IllegalStateException("ARTIST_REFERENCE_ACTIVE_VERSION_INVALID");
    }

    private void flush(String version, List<IktvArtistRegionReferenceReader.Entry> rows) {
        jdbc.batchUpdate(INSERT_REFERENCE_SQL, rows, rows.size(), (PreparedStatement statement,
                                                                   IktvArtistRegionReferenceReader.Entry artist) -> {
            statement.setString(1, version);
            statement.setString(2, artist.artistKey());
            statement.setString(3, artist.displayName());
            statement.setString(4, artist.region());
            statement.setString(5, artist.gender());
            statement.setString(6, artist.sourceCategory());
        });
    }

    private static String reasonCode(Exception exception) {
        if (exception instanceof IllegalStateException && exception.getMessage() != null
                && exception.getMessage().matches("ARTIST_REFERENCE_[A-Z0-9_]{1,56}")) {
            return exception.getMessage();
        }
        return "ARTIST_REFERENCE_IMPORT_FAILED";
    }
}
