package com.homektv.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in search scale regression. It records the real PostgreSQL plan and
 * timing for the same SQL used by SongSearchRepository over a 200k-row
 * temporary catalogue. It is intentionally excluded from the default suite.
 */
@SpringBootTest
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
@EnabledIfSystemProperty(named = "runSearchPerformanceTest", matches = "true")
class SearchLargeLibraryPerformanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv")
            .withUsername("ktv")
            .withPassword("ktv");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private SongSearchService searchService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String fingerprintPrefix;

    @BeforeEach
    void seedLargeLibrary() {
        int rows = Math.max(200_000, Integer.getInteger("searchLibraryRows", 200_000));
        fingerprintPrefix = "search-library-" + UUID.randomUUID() + "-";
        jdbc.update("TRUNCATE TABLE song_search_terms");
        jdbc.update("ALTER TABLE songs DISABLE TRIGGER USER");
        try {
            jdbc.update("""
                    INSERT INTO songs (
                        title, artist, title_py, title_init, artist_py, artist_init,
                        language, tags, media_type, has_vocal_track, duration_ms,
                        lyric_type, play_count, status, fingerprint, artist_gender,
                        vocal_form, metadata_locks, metadata_provenance,
                        needs_ai_optimization, ai_genres, ai_themes
                    )
                    SELECT CASE WHEN g = 9 THEN '歌9' ELSE '压力曲' || g END,
                           '艺' || (g % 1000),
                           CASE WHEN g = 199999 THEN 'qingtian' ELSE '' END,
                           CASE WHEN g = 199999 THEN 'qt' ELSE '' END,
                           '', '', '国语', ARRAY['流行']::text[],
                           CASE WHEN g % 3 = 0 THEN 'MV' ELSE 'KTV_VIDEO' END,
                           FALSE, 180000, 'none', g % 1000, 'ok',
                           ? || g, '未知', '独唱', ARRAY[]::text[], '{}'::jsonb,
                           FALSE, ARRAY['流行']::text[], ARRAY[]::text[]
                    FROM generate_series(1, ?) AS g
                    """, fingerprintPrefix, rows);
        } finally {
            jdbc.update("ALTER TABLE songs ENABLE TRIGGER USER");
        }
        // The scale test isolates the read plan. Its projection contains the
        // exact terms exercised below; trigger-maintained full projection
        // coverage is verified by SearchIntegrationTest.
        jdbc.update("""
                INSERT INTO song_search_terms (song_id, kind, value, value_lower)
                SELECT id, 'TITLE', '歌9', '歌9'
                FROM songs
                WHERE fingerprint LIKE ? AND title = '歌9'
                UNION ALL
                SELECT id, 'TITLE_PY', 'h', 'h'
                FROM songs
                WHERE fingerprint LIKE ?
                UNION ALL
                SELECT id, 'TITLE', '压', '压'
                FROM songs
                WHERE fingerprint LIKE ?
                UNION ALL
                SELECT id, 'TITLE', '压力', '压力'
                FROM songs
                WHERE fingerprint LIKE ? AND title LIKE '压力%'
                UNION ALL
                SELECT songs.id, 'TAG', '行', '行'
                FROM songs
                CROSS JOIN LATERAL unnest(songs.tags) AS tags(tag)
                WHERE songs.fingerprint LIKE ? AND lower(tags.tag) LIKE '%行%'
                UNION ALL
                SELECT songs.id, 'TAG', 'tagmarker', 'tagmarker'
                FROM songs
                WHERE songs.fingerprint LIKE ? AND songs.title = '压力曲199998'
                ON CONFLICT DO NOTHING
                """, fingerprintPrefix + "%", fingerprintPrefix + "%", fingerprintPrefix + "%",
                fingerprintPrefix + "%", fingerprintPrefix + "%", fingerprintPrefix + "%");
    }

    @AfterEach
    void removeSeededSongs() {
        if (fingerprintPrefix != null) {
            jdbc.update("TRUNCATE TABLE song_search_terms");
            jdbc.update("DELETE FROM songs WHERE fingerprint LIKE ?", fingerprintPrefix + "%");
        }
    }

    @Test
    void recordsPlansForShortSelectiveTagAndMediaFilteredSearches() throws Exception {
        List<SearchCase> cases = List.of(
                new SearchCase("short-chinese-selective", "歌9", ""),
                new SearchCase("short-chinese", "压力", ""),
                new SearchCase("high-fanout-latin", "h", ""),
                new SearchCase("high-fanout-chinese", "压", ""),
                new SearchCase("pinyin-prefix", "qingtian", "KTV_VIDEO"),
                new SearchCase("selective-title", "压力曲199999", "KTV_VIDEO"),
                new SearchCase("tag-substring", "marker", "MV")
        );
        long maxMillis = Long.getLong("searchMaxMillis", 8_000L);

        for (SearchCase searchCase : cases) {
            SearchKeyword keyword = SearchKeyword.of(
                    searchCase.keyword(), SongSearchService.MAX_KEYWORD_LENGTH);
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("rawKw", keyword.raw())
                    .addValue("kw", keyword.lower())
                    .addValue("likeKw", keyword.likePattern())
                    .addValue("mediaType", searchCase.mediaType());

            String searchSql = keyword.lower().codePointCount(0, keyword.lower().length()) <= 2
                    ? SongSearchRepository.SHORT_SEARCH_SQL
                    : SongSearchRepository.SEARCH_SQL;
            String explainSql = "EXPLAIN (FORMAT JSON, ANALYZE TRUE, COSTS TRUE, BUFFERS FALSE) "
                    + searchSql + " LIMIT 50 OFFSET 0";
            String planJson = namedJdbc.queryForObject(explainSql, params, String.class);
            JsonNode plan = objectMapper.readTree(planJson);
            Set<String> nodeTypes = nodeTypes(plan, new LinkedHashSet<>());

            long started = System.nanoTime();
            List<Song> result = searchService.search(searchCase.keyword(), searchCase.mediaType(), 0);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

            System.out.printf("search-plan case=%s mediaType=%s elapsedMs=%d nodes=%s%n",
                    searchCase.name(), searchCase.mediaType(), elapsedMillis, nodeTypes);
            assertThat(plan.isArray()).isTrue();
            assertThat(plan.path(0).has("Plan")).isTrue();
            assertThat(nodeTypes).isNotEmpty();
            assertThat(result).hasSizeLessThanOrEqualTo(50);
            if (searchCase.name().equals("short-chinese-selective")) {
                assertThat(relationsWithNode(plan, "Seq Scan", "songs"))
                        .as("short Chinese search must not scan the songs heap")
                        .isEmpty();
                assertThat(nodeTypes)
                        .as("short Chinese search must not expand tags with unnest")
                        .doesNotContain("Function Scan");
            }
            if (maxMillis >= 0) {
                assertThat(elapsedMillis).isLessThanOrEqualTo(maxMillis);
            }
        }
    }

    private static Set<String> nodeTypes(JsonNode node, Set<String> result) {
        if (node == null || node.isMissingNode()) return result;
        if (node.has("Node Type")) result.add(node.get("Node Type").asText());
        node.elements().forEachRemaining(child -> nodeTypes(child, result));
        return result;
    }

    private static Set<String> relationsWithNode(JsonNode node, String nodeType,
                                                  String relationName) {
        Set<String> result = new LinkedHashSet<>();
        collectRelations(node, nodeType, relationName, result);
        return result;
    }

    private static void collectRelations(JsonNode node, String nodeType,
                                         String relationName, Set<String> result) {
        if (node == null || node.isMissingNode()) return;
        if (node.path("Node Type").asText().equals(nodeType)
                && node.path("Relation Name").asText().equals(relationName)) {
            result.add(relationName);
        }
        node.elements().forEachRemaining(child -> collectRelations(child, nodeType, relationName, result));
    }

    private record SearchCase(String name, String keyword, String mediaType) {}
}
