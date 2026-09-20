package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryCatalogStatsServiceTest {

    @Test
    void refreshQueryMustPersistAllPublicStatusCounts() {
        assertThat(LibraryCatalogStatsService.REFRESH_SQL)
                .contains("total_songs")
                .contains("indexed_songs")
                .contains("ready_songs")
                .contains("probe_pending_files")
                .contains("file_role = ?");
    }

    @Test
    void statsMigrationMustProvideSingleRowLookup() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V49__library_catalog_stats.sql")))
                .contains("CREATE TABLE library_catalog_stats")
                .contains("library_key");
    }
}
