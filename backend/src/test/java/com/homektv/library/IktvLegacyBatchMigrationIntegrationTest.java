package com.homektv.library;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures an in-flight pre-V57 worker batch is safely re-queued, not published with missing staging data. */
@EnabledIfEnvironmentVariable(
        named = "HOMEKTV_TEST_PG_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+")
class IktvLegacyBatchMigrationIntegrationTest {
    @Test
    void requeuesLegacyPartialBatchAndRevertsOnlyItsStillOwnedWrites() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("56")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String snapshot = "legacy-batch-" + suffix;
        String fingerprint = "legacy-batch-song-" + suffix;
        String catalogNumber = "1000000750";
        String manualCatalogNumber = "1000000751";
        long songId = jdbc.queryForObject("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                VALUES (?, ?, 'AUDIO', ?)
                RETURNING id
                """, Long.class, "Legacy staged song " + suffix, "Legacy artist " + suffix, fingerprint);
        jdbc.update("UPDATE songs SET catalog_number = ? WHERE id = ?", catalogNumber, songId);
        long manuallyLockedSongId = jdbc.queryForObject("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                VALUES (?, ?, 'AUDIO', ?)
                RETURNING id
                """, Long.class, "Legacy locked song " + suffix, "Legacy artist " + suffix,
                fingerprint + "-locked");
        jdbc.update("UPDATE songs SET catalog_number = ?, metadata_locks = ARRAY['catalogNumber']::text[] WHERE id = ?",
                manualCatalogNumber, manuallyLockedSongId);
        jdbc.update("""
                INSERT INTO iktv_reference_snapshots (
                    snapshot_version, status, expected_song_count, loaded_song_count, snapshot_sha256, activated_at
                ) VALUES (?, 'ACTIVE', 2, 2, ?, now())
                """, snapshot, "b".repeat(64));
        long referenceId = Math.abs((long) suffix.hashCode()) + 1;
        long lockedReferenceId = referenceId + 1;
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, ?, ?, 'Legacy staged song', 'Legacy artist', '', '国语', '测试', '歌曲',
                         'legacy staged song', 'legacy artist', '', '{}', false)
                """, snapshot, referenceId, catalogNumber);
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                ) VALUES (?, ?, ?, 'Legacy locked song', 'Legacy artist', '', '国语', '测试', '歌曲',
                         'legacy locked song', 'legacy artist', '', '{}', false)
                """, snapshot, lockedReferenceId, manualCatalogNumber);
        long batchId = jdbc.queryForObject("""
                INSERT INTO iktv_song_match_batches (batch_kind, snapshot_version, state, total_count)
                VALUES ('INITIAL', ?, 'RUNNING', 2)
                RETURNING id
                """, Long.class, snapshot);
        jdbc.update("""
                UPDATE iktv_song_match_work
                   SET batch_id = ?, state = 'RUNNING', lease_until = now() + interval '1 minute'
                 WHERE song_id IN (?, ?)
                """, batchId, songId, manuallyLockedSongId);
        jdbc.update("""
                INSERT INTO iktv_song_match_batch_items (
                    batch_id, song_id, generation, match_status, reason_code, reference_song_id,
                    catalog_number_changed, previous_catalog_number, applied_catalog_number,
                    classification_changed
                ) VALUES (?, ?, 1, 'MATCHED', 'LEGACY_MATCH', ?, true, NULL, ?, true)
                """, batchId, songId, referenceId, catalogNumber);
        jdbc.update("""
                INSERT INTO iktv_song_match_batch_items (
                    batch_id, song_id, generation, match_status, reason_code, reference_song_id,
                    catalog_number_changed, previous_catalog_number, applied_catalog_number,
                    classification_changed
                ) VALUES (?, ?, 1, 'MATCHED', 'LEGACY_MATCH', ?, true, NULL, ?, true)
                """, batchId, manuallyLockedSongId, lockedReferenceId, manualCatalogNumber);
        jdbc.update("""
                INSERT INTO iktv_song_categories (song_id, snapshot_version, reference_song_id, category)
                VALUES (?, ?, ?, '精选专区'), (?, ?, ?, '精选专区')
                """, songId, snapshot, referenceId, manuallyLockedSongId, snapshot, lockedReferenceId);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT state FROM iktv_song_match_batches WHERE id = ?", String.class, batchId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT publication_state FROM iktv_song_match_batches WHERE id = ?",
                String.class, batchId)).isEqualTo("LEGACY_UNTRACKED");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM iktv_song_match_batches WHERE id = ?",
                String.class, batchId)).isEqualTo("LEGACY_BATCH_REQUEUED_FOR_SAFE_STAGING");
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, songId))
                .isNullOrEmpty();
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class,
                manuallyLockedSongId)).isEqualTo(manualCatalogNumber);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_categories WHERE song_id IN (?, ?)",
                Long.class, songId, manuallyLockedSongId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT batch_id FROM iktv_song_match_work WHERE song_id = ?", Long.class,
                songId)).isNull();
        assertThat(jdbc.queryForObject("SELECT batch_id FROM iktv_song_match_work WHERE song_id = ?", Long.class,
                manuallyLockedSongId)).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM iktv_song_match_work WHERE song_id = ?", String.class,
                songId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT lease_until FROM iktv_song_match_work WHERE song_id = ?",
                java.time.OffsetDateTime.class, songId)).isNull();
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
