package com.homektv;

import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class SearchMigrationContractTest {

    @Test
    void v36ProvidesTheExpressionIndexRequiredByCaseInsensitiveLanguageSearch() throws Exception {
        String sql = Files.readString(migrationPath());

        assertThat(sql).contains("idx_songs_language_lower_prefix");
        assertThat(sql).contains("ON songs (lower(language) text_pattern_ops)");
    }

    @Test
    void v36DoesNotUseConcurrentIndexesInsideTheTransactionalMigration() throws Exception {
        String sql = Files.readString(migrationPath());

        assertThat(sql).doesNotContainIgnoringCase("CREATE INDEX CONCURRENTLY");
    }

    @Test
    void v39RefreshesProjectionWhenPinyinColumnsChange() throws Exception {
        Path migrationDir = Paths.get(migrationPath().toString()).getParent();
        String sql = Files.readString(migrationDir.resolve("V39__refresh_search_terms_pinyin_trigger.sql"));

        assertThat(sql).contains("title_py, title_init, artist_py, artist_init")
                .contains("ktv_sync_song_search_terms");
    }

    @Test
    void v40AddsLowerPrefixIndexesForEveryCaseInsensitivePinyinBranch() throws Exception {
        String sql = Files.readString(migrationPath("V40__search_projection_hot_path.sql"));

        assertThat(sql)
                .contains("lower(title_py) text_pattern_ops")
                .contains("lower(title_init) text_pattern_ops")
                .contains("lower(artist_py) text_pattern_ops")
                .contains("lower(artist_init) text_pattern_ops")
                .contains("ON song_artists (lower(artist_py) text_pattern_ops)")
                .contains("ON song_artists (lower(artist_init) text_pattern_ops)");
    }

    @Test
    void generalSearchDoesNotExpandTagsRowByRow() {
        assertThat(SongSearchRepository.SEARCH_SQL).doesNotContain("unnest(s.tags)");
    }

    @Test
    void shortSearchAvoidsArtificialTruncationAndSimilarityAndUsesPartialAndCoveringIndexes() throws Exception {
        assertThat(SongSearchRepository.SHORT_SEARCH_SQL)
                .doesNotContain("LIMIT 2000")
                .doesNotContain("similarity(");
        assertThat(SongSearchRepository.SINGLE_INITIAL_SEARCH_SQL)
                .doesNotContain("LIMIT 2000");
        assertThat(Files.readString(migrationPath("V47__short_search_covering_index.sql")))
                .contains("value_lower, song_id")
                .contains("INCLUDE (kind)");
        assertThat(Files.readString(migrationPath("V61__search_unbounded_optimization.sql")))
                .contains("idx_songs_ok_title_init_lower_prefix")
                .contains("WHERE status = 'ok'");
    }

    @Test
    void v62AddsArtistDirectoryPrefixAndTrigramIndexesAndGeneralSearchSplitsBranches() throws Exception {
        assertThat(Files.readString(migrationPath("V62__artist_directory_search_indexes.sql")))
                .contains("idx_artist_directory_init_lower_prefix")
                .contains("idx_artist_directory_py_lower_prefix")
                .contains("idx_artist_directory_name_trgm");
        assertThat(SongSearchRepository.SEARCH_SQL)
                .contains("UNION ALL")
                .contains(":kw ~ '^[a-z0-9]+$'");
    }

    @Test
    void v71IndexesAliasesIntoSongSearchTermsAndSearchSqlQueriesAliases() throws Exception {
        String sql = Files.readString(migrationPath("V71__aliases_search_terms_and_scrape_indexes.sql"));
        assertThat(sql)
                .contains("'ALIAS'")
                .contains("aliases ON songs")
                .contains("idx_song_external_matches_suggested_purge");
        assertThat(SongSearchRepository.SEARCH_SQL).contains("term.kind IN ('TAG', 'ALIAS')");
        assertThat(SongSearchRepository.SHORT_SEARCH_SQL).contains("'ALIAS'");
    }

    @Test
    void v77AddsTopNFirstLetterIndexesAndLowerCollaborativeArtistIndexes() throws Exception {
        String sql = Files.readString(migrationPath("V77__search_hot_path_topn_indexes.sql"));
        assertThat(sql)
                .contains("idx_songs_ok_title_init_first_play")
                .contains("idx_songs_ok_title_init_first_media_play")
                .contains("idx_songs_ok_artist_init_first_play")
                .contains("idx_song_artists_name_lower_trgm")
                .contains("idx_song_files_valid_song_id");
    }

    @Test
    void v78AddsTwoCharPrefixAndLanguagePlayCountTopNIndexes() throws Exception {
        String sql = Files.readString(migrationPath("V78__search_topn_and_browse_hot_path_indexes.sql"));
        assertThat(sql)
                .contains("idx_songs_ok_title_init_prefix2_play")
                .contains("idx_songs_ok_artist_init_prefix2_play")
                .contains("idx_songs_ok_title_first_play")
                .contains("idx_songs_ok_artist_first_play")
                .contains("idx_songs_ok_language_lower_play");
    }

    private Path migrationPath() throws IOException {
        return migrationPath("V36__song_search_hot_path_indexes.sql");
    }

    private Path migrationPath(String filename) throws IOException {
        try {
            URI resource = getClass().getClassLoader()
                    .getResource("db/migration/" + filename)
                    .toURI();
            return Paths.get(resource);
        } catch (Exception exception) {
            throw new IOException("Cannot resolve migration " + filename, exception);
        }
    }
}
