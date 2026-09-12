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
