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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Imports only the validated, packaged metadata snapshot into reference tables.
 * It never inserts into songs, downloads media, or accesses the NAS.
 */
@Component
public final class IktvReferenceCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(IktvReferenceCatalogLoader.class);
    private static final int BATCH_SIZE = 500;
    private static final String SNAPSHOT_RESOURCE = "classpath:iktv/iktv-reference-catalog.jsonl.gz";
    private static final String MANIFEST_RESOURCE = "classpath:iktv/iktv-reference-catalog.jsonl.manifest.json";
    private static final String INSERT_REFERENCE_SQL = """
            INSERT INTO iktv_reference_songs (
                snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                edition, language, category, song_type, normalized_title, normalized_artist,
                normalized_edition, special_tags, special_tag_conflict
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (snapshot_version, reference_song_id) DO UPDATE SET
                catalog_number = EXCLUDED.catalog_number,
                title = EXCLUDED.title,
                artist_credit = EXCLUDED.artist_credit,
                edition = EXCLUDED.edition,
                language = EXCLUDED.language,
                category = EXCLUDED.category,
                song_type = EXCLUDED.song_type,
                normalized_title = EXCLUDED.normalized_title,
                normalized_artist = EXCLUDED.normalized_artist,
                normalized_edition = EXCLUDED.normalized_edition,
                special_tags = EXCLUDED.special_tags,
                special_tag_conflict = EXCLUDED.special_tag_conflict
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ResourceLoader resourceLoader;
    private final IktvReferenceCatalogSnapshotReader snapshotReader;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public IktvReferenceCatalogLoader(JdbcTemplate jdbcTemplate,
                                      PlatformTransactionManager transactionManager,
                                      ResourceLoader resourceLoader,
                                      IktvReferenceCatalogSnapshotReader snapshotReader,
                                      @Qualifier("iktvReferenceCatalogExecutor") Executor executor) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.resourceLoader = resourceLoader;
        this.snapshotReader = snapshotReader;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.iktv-reference.retry-delay-ms:300000}",
            initialDelayString = "${app.iktv-reference.initial-delay-ms:30000}")
    public void scheduleSnapshotImport() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    importPackagedSnapshotIfNeeded();
                } catch (Exception exception) {
                    log.error("IKTV reference snapshot import failed; existing active version is retained",
                            exception);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.warn("IKTV reference snapshot import was not scheduled: {}", rejected.getClass().getSimpleName());
        }
    }

    void importPackagedSnapshotIfNeeded() throws Exception {
        Resource manifestResource = resourceLoader.getResource(MANIFEST_RESOURCE);
        Resource snapshotResource = resourceLoader.getResource(SNAPSHOT_RESOURCE);
        if (!manifestResource.exists() || !snapshotResource.exists()) {
            throw new IllegalStateException("SNAPSHOT_RESOURCE_MISSING");
        }

        IktvReferenceCatalogSnapshotReader.Manifest manifest;
        try (InputStream input = manifestResource.getInputStream()) {
            manifest = snapshotReader.readManifest(input);
        }
        if (isActive(manifest.snapshotVersion())) return;

        jdbcTemplate.update("""
                INSERT INTO iktv_reference_snapshots (
                    snapshot_version, status, expected_song_count, loaded_song_count, snapshot_sha256,
                    last_error_code, updated_at
                ) VALUES (?, 'STAGING', ?, 0, ?, NULL, now())
                ON CONFLICT (snapshot_version) DO UPDATE SET
                    status = 'STAGING',
                    expected_song_count = EXCLUDED.expected_song_count,
                    loaded_song_count = 0,
                    snapshot_sha256 = EXCLUDED.snapshot_sha256,
                    last_error_code = NULL,
                    updated_at = now()
                WHERE iktv_reference_snapshots.status <> 'ACTIVE'
                """, manifest.snapshotVersion(), manifest.referenceSongCount(), manifest.snapshotSha256());
        jdbcTemplate.update("DELETE FROM iktv_reference_songs WHERE snapshot_version = ?",
                manifest.snapshotVersion());

        List<IktvSongReferenceMatcher.ReferenceSong> batch = new ArrayList<>(BATCH_SIZE);
        long rows;
        try (InputStream input = snapshotResource.getInputStream()) {
            rows = snapshotReader.streamRows(input, manifest, song -> {
                batch.add(song);
                if (batch.size() == BATCH_SIZE) flushBatch(manifest.snapshotVersion(), batch);
            });
        } catch (Exception exception) {
            markFailed(manifest.snapshotVersion(), reasonCode(exception));
            throw exception;
        }
        if (!batch.isEmpty()) flushBatch(manifest.snapshotVersion(), batch);

        int updated = jdbcTemplate.update("""
                UPDATE iktv_reference_snapshots
                   SET loaded_song_count = ?, last_error_code = NULL, updated_at = now()
                 WHERE snapshot_version = ? AND status = 'STAGING'
                """, rows, manifest.snapshotVersion());
        if (updated != 1) throw new IllegalStateException("SNAPSHOT_STAGING_ROW_MISSING");

        activateStagedSnapshot(manifest.snapshotVersion(), manifest.referenceSongCount());
        log.info("Validated IKTV reference snapshot activated: version={}, rows={}",
                manifest.snapshotVersion(), rows);
    }

    void activateStagedSnapshot(String snapshotVersion, long expectedSongCount) {
        transactionTemplate.executeWithoutResult(transaction -> {
            Long loaded = jdbcTemplate.queryForObject("""
                    SELECT loaded_song_count FROM iktv_reference_snapshots
                     WHERE snapshot_version = ? AND status = 'STAGING' FOR UPDATE
                    """, Long.class, snapshotVersion);
            if (loaded == null || loaded != expectedSongCount) {
                throw new IllegalStateException("SNAPSHOT_LOADED_COUNT_MISMATCH");
            }
            String previousVersion = jdbcTemplate.query("""
                    SELECT snapshot_version FROM iktv_reference_snapshots
                     WHERE status = 'ACTIVE' FOR UPDATE
                    """, rs -> rs.next() ? rs.getString(1) : null);
            if (previousVersion != null && !previousVersion.equals(snapshotVersion)) {
                carryForwardUnchangedSnapshotState(previousVersion, snapshotVersion);
            }
            jdbcTemplate.update("UPDATE iktv_reference_snapshots SET status = 'RETIRED', updated_at = now() WHERE status = 'ACTIVE'");
            int activated = jdbcTemplate.update("""
                    UPDATE iktv_reference_snapshots
                       SET status = 'ACTIVE', activated_at = now(), updated_at = now()
                     WHERE snapshot_version = ? AND status = 'STAGING'
                    """, snapshotVersion);
            if (activated != 1) throw new IllegalStateException("SNAPSHOT_ACTIVATION_FAILED");
        });
    }

    /**
     * Snapshot-scoped match and category rows must survive activation when their
     * reference identity did not change. Changed results stay on the old version
     * so the bounded match worker can reconcile them against the new snapshot.
     */
    private void carryForwardUnchangedSnapshotState(String previousVersion, String nextVersion) {
        jdbcTemplate.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                SELECT category.song_id, ?, category.reference_song_id, category.category
                  FROM iktv_song_categories category
                  JOIN iktv_reference_songs previous_reference
                    ON previous_reference.snapshot_version = category.snapshot_version
                   AND previous_reference.reference_song_id = category.reference_song_id
                  JOIN iktv_reference_songs next_reference
                    ON next_reference.snapshot_version = ?
                   AND next_reference.reference_song_id = previous_reference.reference_song_id
                 WHERE category.snapshot_version = ?
                   AND ROW(previous_reference.catalog_number, previous_reference.title,
                           previous_reference.artist_credit, previous_reference.edition,
                           previous_reference.language, previous_reference.category,
                           previous_reference.song_type, previous_reference.normalized_title,
                           previous_reference.normalized_artist, previous_reference.normalized_edition,
                           previous_reference.special_tags, previous_reference.special_tag_conflict)
                       IS NOT DISTINCT FROM
                       ROW(next_reference.catalog_number, next_reference.title,
                           next_reference.artist_credit, next_reference.edition,
                           next_reference.language, next_reference.category,
                           next_reference.song_type, next_reference.normalized_title,
                           next_reference.normalized_artist, next_reference.normalized_edition,
                           next_reference.special_tags, next_reference.special_tag_conflict)
                ON CONFLICT DO NOTHING
                """, nextVersion, nextVersion, previousVersion);

        jdbcTemplate.update("""
                WITH changed AS (
                    (SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?
                     EXCEPT
                     SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?)
                    UNION
                    (SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?
                     EXCEPT
                     SELECT reference_song_id, catalog_number, normalized_title, normalized_artist,
                            normalized_edition, edition, language, category, song_type,
                            special_tags, special_tag_conflict
                       FROM iktv_reference_songs WHERE snapshot_version = ?)
                ), changed_ids AS (
                    SELECT DISTINCT reference_song_id FROM changed
                ), changed_numbers AS (
                    SELECT DISTINCT catalog_number FROM changed WHERE catalog_number <> ''
                )
                UPDATE iktv_song_match_results result
                   SET snapshot_version = ?
                 WHERE result.snapshot_version = ?
                   AND NOT (
                       result.reference_song_id IN (SELECT reference_song_id FROM changed_ids)
                       OR EXISTS (
                           SELECT 1 FROM changed changed_key
                            WHERE changed_key.normalized_title = result.normalized_title
                              AND changed_key.normalized_artist = result.normalized_artist
                       )
                       OR result.home_catalog_number IN (SELECT catalog_number FROM changed_numbers)
                       OR result.number_clues && ARRAY(SELECT catalog_number::text FROM changed_numbers)
                   )
                """, previousVersion, nextVersion, nextVersion, previousVersion,
                nextVersion, previousVersion);
    }

    private boolean isActive(String version) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iktv_reference_snapshots
                 WHERE snapshot_version = ? AND status = 'ACTIVE'
                """, Integer.class, version);
        return count != null && count == 1;
    }

    private void flushBatch(String version, List<IktvSongReferenceMatcher.ReferenceSong> rows) {
        jdbcTemplate.batchUpdate(INSERT_REFERENCE_SQL, rows, rows.size(), (PreparedStatement statement,
                                                                           IktvSongReferenceMatcher.ReferenceSong song) -> {
            statement.setString(1, version);
            statement.setLong(2, song.referenceSongId());
            statement.setString(3, song.catalogNumber());
            statement.setString(4, song.title());
            statement.setString(5, song.artist());
            statement.setString(6, song.edition());
            statement.setString(7, song.language());
            statement.setString(8, song.category());
            statement.setString(9, song.type());
            statement.setString(10, IktvSongReferenceMatcher.normalize(song.title()));
            statement.setString(11, IktvSongReferenceMatcher.normalize(song.artist()));
            statement.setString(12, IktvSongReferenceMatcher.normalize(song.edition()));
            statement.setArray(13, statement.getConnection().createArrayOf("text", song.specialTags().toArray()));
            statement.setBoolean(14, song.specialTagConflict());
        });
        jdbcTemplate.update("""
                UPDATE iktv_reference_snapshots
                   SET loaded_song_count = loaded_song_count + ?, updated_at = now()
                 WHERE snapshot_version = ? AND status = 'STAGING'
                """, rows.size(), version);
        rows.clear();
    }

    private void markFailed(String version, String reason) {
        jdbcTemplate.update("""
                UPDATE iktv_reference_snapshots
                   SET status = 'FAILED', last_error_code = ?, updated_at = now()
                 WHERE snapshot_version = ? AND status = 'STAGING'
                """, reason, version);
    }

    private static String reasonCode(Exception exception) {
        if (exception instanceof IllegalStateException && exception.getMessage() != null
                && exception.getMessage().matches("SNAPSHOT_[A-Z0-9_]+")) {
            return exception.getMessage();
        }
        return "SNAPSHOT_IMPORT_FAILED";
    }
}
