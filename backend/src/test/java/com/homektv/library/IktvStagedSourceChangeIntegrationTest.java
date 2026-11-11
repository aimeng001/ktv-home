package com.homektv.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A source-path edit after staging must invalidate the stale proposal before any publication. */
@EnabledIfEnvironmentVariable(
        named = "HOMEKTV_TEST_PG_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+")
class IktvStagedSourceChangeIntegrationTest {
    @Test
    void changedCatalogPathIsRecomputedInsideTheStagedBatchWithoutStrandingStableResults() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        IktvSongMatchBatchService batchService = new IktvSongMatchBatchService(
                jdbc, transactions, new CatalogRevisionService(jdbc));
        IktvSongMatchWorker worker = new IktvSongMatchWorker(jdbc, transactions,
                new IktvSongReferenceMatcher(), new IktvMetadataEnrichmentService(), batchService, Runnable::run);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String snapshot = "source-change-" + suffix;
        String title = "Path identity target " + suffix;
        String artist = "Path identity artist " + suffix;
        long matchedSongId = newSong(jdbc, title, artist, "source-change-matched-" + suffix);
        String stableTitle = "Stable staged target " + suffix;
        String stableArtist = "Stable staged artist " + suffix;
        long stableSongId = newSong(jdbc, stableTitle, stableArtist, "source-change-stable-" + suffix);
        long deferredSongId = newSong(jdbc, "Deferred path target " + suffix, artist,
                "source-change-deferred-" + suffix);
        jdbc.update("""
                INSERT INTO song_files (
                    song_id, file_path, relative_path, format, file_mtime, valid, file_role, source_deleted
                ) VALUES (?, ?, ?, 'mkv', now(), true, 'LIBRARY', false)
                """, matchedSongId, "T:/library/60000001-" + title + ".mkv", "60000001-" + title + ".mkv");
        jdbc.update("""
                INSERT INTO song_files (
                    song_id, file_path, relative_path, format, file_mtime, valid, file_role, source_deleted
                ) VALUES (?, ?, ?, 'mkv', now(), true, 'LIBRARY', false)
                """, stableSongId, "T:/library/60000003-" + stableTitle + ".mkv",
                "60000003-" + stableTitle + ".mkv");
        jdbc.update("""
                INSERT INTO iktv_reference_snapshots (
                    snapshot_version, status, expected_song_count, loaded_song_count, snapshot_sha256, activated_at
                ) VALUES (?, 'ACTIVE', 2, 2, ?, now())
                """, snapshot, "c".repeat(64));
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, 61000001, '60000001', ?, ?, '', '国语', '测试', '歌曲', ?, ?, '', ARRAY['抖音神曲'], false)
                """, snapshot, title, artist, IktvSongReferenceMatcher.normalize(title),
                IktvSongReferenceMatcher.normalize(artist));
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, 61000002, '60000003', ?, ?, '', '国语', '测试', '歌曲', ?, ?, '', ARRAY['精选专区'], false)
                """, snapshot, stableTitle, stableArtist,
                IktvSongReferenceMatcher.normalize(stableTitle), IktvSongReferenceMatcher.normalize(stableArtist));

        ReflectionTestUtils.invokeMethod(worker, "ensureWorkBatches");
        jdbc.update("""
                UPDATE iktv_song_match_work SET available_at = now() + interval '1 hour'
                 WHERE song_id = ?
                """, deferredSongId);
        ReflectionTestUtils.invokeMethod(worker, "drainWork");

        long batchId = jdbc.queryForObject("SELECT id FROM iktv_song_match_batches WHERE snapshot_version = ?",
                Long.class, snapshot);
        assertThat(jdbc.queryForObject("SELECT match_status FROM iktv_song_match_batch_items WHERE batch_id = ? AND song_id = ?",
                String.class, batchId, matchedSongId)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("SELECT match_status FROM iktv_song_match_batch_items WHERE batch_id = ? AND song_id = ?",
                String.class, batchId, stableSongId)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, matchedSongId))
                .isNullOrEmpty();

        jdbc.update("""
                UPDATE song_files SET file_path = ?, relative_path = ? WHERE song_id = ?
                """, "T:/library/60000002-" + title + ".mkv", "60000002-" + title + ".mkv",
                matchedSongId);
        assertThat(jdbc.queryForObject(
                "SELECT match_status FROM iktv_song_match_batch_items WHERE batch_id = ? AND song_id = ?",
                String.class, batchId, matchedSongId)).isEqualTo("STALE_GENERATION");

        jdbc.update("UPDATE iktv_song_match_work SET available_at = now() WHERE song_id = ?", deferredSongId);
        ReflectionTestUtils.invokeMethod(worker, "drainWork");

        assertThat(jdbc.queryForObject("SELECT state FROM iktv_song_match_batches WHERE id = ?", String.class, batchId))
                .as("an unrelated stale source item must be recomputed so the complete initial batch can publish")
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT publication_state FROM iktv_song_match_batches WHERE id = ?",
                String.class, batchId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, matchedSongId))
                .isNullOrEmpty();
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, stableSongId))
                .isEqualTo("60000003");
        assertThat(jdbc.queryForObject("""
                SELECT category FROM iktv_song_categories WHERE song_id = ? AND snapshot_version = ?
                """, String.class, stableSongId, snapshot)).isEqualTo("精选专区");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_categories WHERE song_id = ?", Long.class,
                matchedSongId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_match_work", Long.class)).isZero();
    }

    private long newSong(JdbcTemplate jdbc, String title, String artist, String fingerprint) {
        return jdbc.queryForObject("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                VALUES (?, ?, 'AUDIO', ?)
                RETURNING id
                """, Long.class, title, artist, fingerprint);
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
