package com.homektv.library;

import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in scale evidence for first-run catalog matching using isolated PostgreSQL only. */
@EnabledIfEnvironmentVariable(
        named = "HOMEKTV_TEST_PG_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+")
@EnabledIfSystemProperty(named = "runIktvScaleTest", matches = "true")
class IktvFusionScaleIntegrationTest {
    private static final int DEFAULT_ROWS = 200_000;
    private HikariDataSource dataSource;

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void processesTwoHundredThousandSyntheticHomeSongsThroughTheBatchPipeline() {
        dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        CatalogRevisionService catalogRevision = new CatalogRevisionService(jdbc);
        IktvSongMatchBatchService batchService = new IktvSongMatchBatchService(
                jdbc, transactions, catalogRevision);
        IktvSongMatchWorker worker = new IktvSongMatchWorker(jdbc, transactions,
                new IktvSongReferenceMatcher(), new IktvMetadataEnrichmentService(), batchService, Runnable::run);

        int totalRows = Integer.getInteger("iktvScaleRows", DEFAULT_ROWS);
        int matchedRows = Math.max(1, totalRows / 10);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String snapshot = "iktv-scale-" + suffix;
        String fingerprintPrefix = "iktv-scale-" + suffix + "-";

        jdbc.update("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                SELECT CASE WHEN g <= ? THEN 'iktv scale matched title ' || g
                            ELSE 'iktv scale unmatched title ' || g END,
                       CASE WHEN g <= ? THEN 'iktv scale artist ' || g
                            ELSE 'iktv scale unmatched artist ' || g END,
                       'AUDIO', ? || g
                  FROM generate_series(1, ?) AS g
                """, matchedRows, matchedRows, fingerprintPrefix, totalRows);
        jdbc.update("""
                INSERT INTO iktv_reference_snapshots (
                    snapshot_version, status, expected_song_count, loaded_song_count,
                    snapshot_sha256, activated_at
                ) VALUES (?, 'ACTIVE', ?, ?, ?, now())
                """, snapshot, matchedRows, matchedRows, "d".repeat(64));
        jdbc.update("""
                INSERT INTO iktv_reference_songs (
                    snapshot_version, reference_song_id, catalog_number, title, artist_credit,
                    edition, language, category, song_type, normalized_title, normalized_artist,
                    normalized_edition, special_tags, special_tag_conflict
                )
                SELECT ?, 8000000000 + g, lpad((10000000 + g)::text, 8, '0'),
                       'iktv scale matched title ' || g, 'iktv scale artist ' || g,
                       '', '国语', '测试', '歌曲',
                       'iktv scale matched title ' || g, 'iktv scale artist ' || g,
                       '', ARRAY['精选专区']::text[], false
                  FROM generate_series(1, ?) AS g
                """, snapshot, matchedRows);

        long startedAt = System.nanoTime();
        int maximumTicks = (totalRows + 499) / 500 + 10;
        int ticks = 0;
        while (ticks < maximumTicks) {
            ReflectionTestUtils.invokeMethod(worker, "drainWork");
            ticks++;
            String state = jdbc.queryForObject("""
                    SELECT state FROM iktv_song_match_batches WHERE batch_kind = 'INITIAL'
                    """, String.class);
            if ("COMPLETED".equals(state)) break;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        Long batchId = jdbc.queryForObject("""
                SELECT id FROM iktv_song_match_batches WHERE batch_kind = 'INITIAL'
                """, Long.class);
        assertThat(batchId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT state FROM iktv_song_match_batches WHERE id = ?",
                String.class, batchId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT publication_state FROM iktv_song_match_batches WHERE id = ?",
                String.class, batchId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT total_count FROM iktv_song_match_batches WHERE id = ?",
                Long.class, batchId)).isEqualTo((long) totalRows);
        assertThat(jdbc.queryForObject("SELECT processed_count FROM iktv_song_match_batches WHERE id = ?",
                Long.class, batchId)).isEqualTo((long) totalRows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_song_match_results WHERE batch_id = ?",
                Long.class, batchId)).isEqualTo((long) totalRows);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM songs
                 WHERE fingerprint LIKE ? AND catalog_number <> ''
                """, Long.class, fingerprintPrefix + "%")).isEqualTo((long) matchedRows);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_categories category
                  JOIN songs song ON song.id = category.song_id
                 WHERE song.fingerprint LIKE ? AND category.snapshot_version = ?
                """, Long.class, fingerprintPrefix + "%", snapshot)).isEqualTo((long) matchedRows);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iktv_song_match_batch_publications WHERE batch_id = ?
                """, Long.class, batchId)).isEqualTo((long) totalRows);

        double rowsPerSecond = totalRows / Math.max(0.001d, elapsed.toNanos() / 1_000_000_000d);
        System.out.printf("IKTV synthetic scale: rows=%d matched=%d ticks=%d elapsed_ms=%d rows_per_second=%.1f%n",
                totalRows, matchedRows, ticks, elapsed.toMillis(), rowsPerSecond);
        long maximumSeconds = Long.getLong("iktvScaleMaxSeconds", 0L);
        if (maximumSeconds > 0) {
            assertThat(elapsed.toSeconds()).as("configured synthetic fusion throughput limit")
                    .isLessThanOrEqualTo(maximumSeconds);
        }
    }

    private HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required("HOMEKTV_TEST_PG_URL"));
        config.setUsername(required("HOMEKTV_TEST_PG_USER"));
        config.setPassword(required("HOMEKTV_TEST_PG_PASSWORD"));
        // Match Spring Boot's production Hikari default pool size. The single
        // worker still processes serially, but each per-item transaction must
        // reuse a pooled physical connection instead of opening a new socket.
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(10);
        config.setPoolName("iktv-scale-test");
        return new HikariDataSource(config);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }
}
