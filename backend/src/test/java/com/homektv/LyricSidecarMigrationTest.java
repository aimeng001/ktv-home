package com.homektv;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LyricSidecarMigrationTest {

    private static final Pattern DESTRUCTIVE_STATEMENT = Pattern.compile(
            "(?im)^\\s*(DELETE\\s+FROM|TRUNCATE\\s+(?:TABLE\\s+)?|DROP\\s+(?:TABLE|COLUMN)\\s+)"
    );

    @Test
    void v20AddsIndependentMediaAndLyricSnapshotsWithSafeLegacyDefaults() throws Exception {
        String sql = Files.readString(migrationPath());

        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS media_mtime");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS media_file_identity");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lyric_size");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lyric_mtime");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lyric_file_identity");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lyric_snapshot_version");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lyric_source");
        assertThat(sql).contains("DEFAULT 'UNKNOWN'");
        assertThat(sql).doesNotMatch(DESTRUCTIVE_STATEMENT);
    }

    private Path migrationPath() throws Exception {
        URI resource = getClass().getClassLoader()
                .getResource("db/migration/V20__lyric_sidecar_snapshots.sql").toURI();
        return Paths.get(resource);
    }
}
