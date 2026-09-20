package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistDirectoryProjectionServiceTest {

    @Test
    void refreshQueryMustUseSingleCreditGenderAndPreserveManualProfiles() {
        assertThat(ArtistDirectoryProjectionService.REFRESH_SQL)
                .contains("credit_count = 1")
                .contains("count(DISTINCT artist_gender) = 1")
                .contains("gender_status = 'MANUAL'");
    }

    @Test
    void projectionMigrationMustProvideLookupIndexes() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V48__artist_directory_projection.sql");
        assertThat(Files.readString(migration))
                .contains("CREATE TABLE artist_directory_stats")
                .contains("idx_artist_directory_stats_lookup")
                .contains("artist_directory_projection_state");
    }
}
