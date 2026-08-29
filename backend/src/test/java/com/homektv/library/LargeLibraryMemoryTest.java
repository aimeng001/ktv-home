package com.homektv.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in scale regression. It deliberately stays out of the default suite because it
 * seeds a large temporary database; run with runLargeLibraryTest=true and Xmx512m.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "runLargeLibraryTest", matches = "true")
class LargeLibraryMemoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv")
            .withUsername("ktv")
            .withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CategoryBrowseService categoryBrowse;

    @Autowired
    private ArtistLibraryService artistLibrary;

    private String fingerprintPrefix;
    private int seededRows;

    @BeforeEach
    void seedLargeLibrary() {
        int rows = Math.max(5_001, Integer.getInteger("largeLibraryRows", 200_000));
        seededRows = rows;
        fingerprintPrefix = "large-library-" + UUID.randomUUID() + "-";
        jdbc.update("""
                INSERT INTO songs (
                    title, artist, title_py, title_init, artist_py, artist_init,
                    language, tags, media_type, has_vocal_track, duration_ms,
                    lyric_type, play_count, status, fingerprint, artist_gender,
                    vocal_form, metadata_locks, metadata_provenance,
                    needs_ai_optimization, ai_genres, ai_themes
                )
                SELECT '压力歌曲-' || g,
                       '压力歌手-' || (g % 1000),
                       '', '', '', '', '国语', ARRAY['流行']::text[],
                       'KTV_VIDEO', FALSE, 180000, 'none', 0, 'ok',
                       ? || g, '未知', '独唱', ARRAY[]::text[], '{}'::jsonb,
                       FALSE, ARRAY['流行']::text[], ARRAY[]::text[]
                FROM generate_series(1, ?) AS g
                """, fingerprintPrefix, rows);
    }

    @AfterEach
    void removeSeededSongs() {
        if (fingerprintPrefix != null) {
            jdbc.update("DELETE FROM songs WHERE fingerprint LIKE ?", fingerprintPrefix + "%");
        }
    }

    @Test
    void pagedBrowseAndArtistAggregationSurviveConcurrentLargeLibraryReads() throws Exception {
        assertThat(categoryBrowse.artists()).isNotEmpty();
        assertThat(categoryBrowse.tags()).isNotEmpty();
        assertThat(artistLibrary.list("压力歌手-1", null, null, 100)).isNotEmpty();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> reads = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                int artistIndex = index;
                reads.add(() -> {
                    categoryBrowse.artists();
                    categoryBrowse.languages();
                    categoryBrowse.tags();
                    categoryBrowse.songs("压力歌手-" + artistIndex, null, null, null, null, "new", 100);
                    artistLibrary.list("压力歌手-" + artistIndex, null, null, 100);
                    return null;
                });
            }
            List<Future<Void>> futures = reads.stream().map(executor::submit).toList();
            for (Future<Void> future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM songs WHERE fingerprint LIKE ?", Long.class,
                fingerprintPrefix + "%")).isEqualTo((long) seededRows);
    }
}
