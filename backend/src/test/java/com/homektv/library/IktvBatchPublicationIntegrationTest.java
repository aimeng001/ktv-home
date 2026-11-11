package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL 16-only proof for the batch publication boundary. */
@EnabledIfEnvironmentVariable(
        named = "HOMEKTV_TEST_PG_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+")
class IktvBatchPublicationIntegrationTest {
    @Test
    void incompleteBatchDoesNotPublishSongNumberOrCategories() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        CatalogRevisionService catalogRevision = new CatalogRevisionService(jdbc);
        IktvSongMatchBatchService batchService = new IktvSongMatchBatchService(
                jdbc, transactions, catalogRevision);
        IktvSongMatchWorker worker = new IktvSongMatchWorker(jdbc, transactions,
                new IktvSongReferenceMatcher(), new IktvMetadataEnrichmentService(),
                batchService, Runnable::run);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String version = "batch-gate-" + suffix;
        long matched = newSong(jdbc, "Fusion target " + suffix, "Fusion artist " + suffix,
                "fingerprint-" + suffix + "-a");
        long deferred = newSong(jdbc, "Deferred target " + suffix, "Deferred artist " + suffix,
                "fingerprint-" + suffix + "-b");

        jdbc.update("""
                INSERT INTO iktv_reference_snapshots (
                    snapshot_version, status, expected_song_count, loaded_song_count,
                    snapshot_sha256, activated_at
                ) VALUES (?, 'ACTIVE', 2, 2, ?, now())
                """, version, "a".repeat(64));
        long referenceId = Math.abs((long) suffix.hashCode()) + 1;
        String catalogNumber = String.format("%08d", referenceId % 100_000_000L);
        long priorReferenceId = referenceId + 1;
        String title = "Fusion target " + suffix;
        String artist = "Fusion artist " + suffix;
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, ?, ?, ?, ?, '', '国语', '测试', '歌曲', ?, ?, '', ARRAY['精选专区'], false)
                """, version, referenceId, catalogNumber, title, artist,
                IktvSongReferenceMatcher.normalize(title), IktvSongReferenceMatcher.normalize(artist));
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, ?, ?, 'Prior category reference', 'Reference artist', '', '国语', '测试', '歌曲',
                         'prior category reference', 'reference artist', '', '{}', false)
                """, version, priorReferenceId, String.format("%08d", priorReferenceId % 100_000_000L));
        jdbc.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                VALUES (?, ?, ?, '热歌')
                """, matched, version, priorReferenceId);

        ReflectionTestUtils.invokeMethod(worker, "ensureWorkBatches");
        jdbc.update("""
                UPDATE iktv_song_match_work
                   SET available_at = now() + interval '1 hour'
                 WHERE song_id = ?
                """, deferred);

        ReflectionTestUtils.invokeMethod(worker, "drainWork");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_match_batch_items WHERE song_id = ?",
                Long.class, matched)).as("the first item must have been successfully staged").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT match_status FROM iktv_song_match_batch_items WHERE song_id = ?",
                String.class, matched)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, matched))
                .as("the matched proposal must remain staged until every batch item is validated")
                .isNullOrEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_categories WHERE song_id = ?", Long.class,
                matched)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT category FROM iktv_song_categories WHERE song_id = ?", String.class,
                matched)).isEqualTo("热歌");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_match_batch_item_categories WHERE song_id = ?",
                Long.class, matched)).isEqualTo(1L);
        long initialBatchId = jdbc.queryForObject("SELECT id FROM iktv_song_match_batches WHERE snapshot_version = ?",
                Long.class, version);
        assertThat(batchService.batchStatus(initialBatchId).state()).isNotEqualTo("COMPLETED");

        jdbc.update("UPDATE iktv_song_match_work SET available_at = now() WHERE song_id = ?", deferred);
        ReflectionTestUtils.invokeMethod(worker, "drainWork");
        assertThat(batchService.batchStatus(initialBatchId).state()).isEqualTo("COMPLETED");
        assertThat(batchService.batchStatus(initialBatchId).publicationState()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, matched))
                .isEqualTo(catalogNumber);
        assertThat(jdbc.queryForObject("""
                SELECT category FROM iktv_song_categories WHERE song_id = ? AND snapshot_version = ?
                """, String.class, matched, version)).isEqualTo("精选专区");

        IktvSongMatchBatchService.RollbackResult rollback = batchService.rollback(initialBatchId);
        assertThat(rollback.state()).isEqualTo("ROLLED_BACK");
        assertThat(rollback.catalogNumbersChanged()).isEqualTo(1L);
        assertThat(rollback.categoriesChanged()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, matched))
                .isNullOrEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT reference_song_id FROM iktv_song_categories WHERE song_id = ? AND snapshot_version = ?
                """, Long.class, matched, version)).isEqualTo(priorReferenceId);
        assertThat(batchService.status().pausedSnapshotVersion()).isEqualTo(version);

        long queuedWhilePaused = newSong(jdbc, "Added while paused " + suffix, "Other artist " + suffix,
                "fingerprint-" + suffix + "-paused");
        ReflectionTestUtils.invokeMethod(worker, "drainWork");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_match_work WHERE song_id = ? AND batch_id IS NULL",
                Long.class, queuedWhilePaused)).isEqualTo(1L);
        assertThat(songsCatalogNumber(jdbc, queuedWhilePaused)).isNullOrEmpty();

        assertThat(batchService.resume().state()).isEqualTo("RESUMED");
        ReflectionTestUtils.invokeMethod(worker, "drainWork");
        long incrementalBatchId = jdbc.queryForObject("""
                SELECT id FROM iktv_song_match_batches WHERE batch_kind = 'INCREMENTAL' ORDER BY id DESC LIMIT 1
                """, Long.class);
        assertThat(batchService.batchStatus(incrementalBatchId).publicationState()).isEqualTo("PUBLISHED");

        String manualCatalogNumber = "87654321";
        jdbc.update("UPDATE songs SET catalog_number = ? WHERE id = ?", manualCatalogNumber, matched);
        jdbc.update("DELETE FROM iktv_song_categories WHERE song_id = ? AND snapshot_version = ?", matched, version);
        jdbc.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                VALUES (?, ?, ?, '怀旧')
                """, matched, version, priorReferenceId);
        IktvSongMatchBatchService.RollbackResult guardedRollback = batchService.rollback(incrementalBatchId);
        assertThat(guardedRollback.state()).isEqualTo("ROLLBACK_PARTIAL");
        assertThat(guardedRollback.conflictingCategoriesOrNumbers()).isEqualTo(2L);
        assertThat(guardedRollback.conflictRows()).isEqualTo(1L);
        assertThat(songsCatalogNumber(jdbc, matched)).isEqualTo(manualCatalogNumber);
        assertThat(jdbc.queryForObject("""
                SELECT category FROM iktv_song_categories WHERE song_id = ? AND snapshot_version = ?
                """, String.class, matched, version)).isEqualTo("怀旧");
        assertThat(batchService.status().pausedSnapshotVersion()).isEqualTo(version);
    }

    private long newSong(JdbcTemplate jdbc, String title, String artist, String fingerprint) {
        return jdbc.queryForObject("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                VALUES (?, ?, 'AUDIO', ?)
                RETURNING id
                """, Long.class, title, artist, fingerprint);
    }

    private String songsCatalogNumber(JdbcTemplate jdbc, long songId) {
        return jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, songId);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(required("HOMEKTV_TEST_PG_URL"));
        dataSource.setUser(required("HOMEKTV_TEST_PG_USER"));
        dataSource.setPassword(required("HOMEKTV_TEST_PG_PASSWORD"));
        return dataSource;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }
}
