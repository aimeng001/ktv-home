package com.homektv;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackPositionMigrationTest {

    private static final Pattern DESTRUCTIVE_STATEMENT = Pattern.compile(
            "(?im)^\\s*(DELETE\\s+FROM|TRUNCATE\\s+(?:TABLE\\s+)?|DROP\\s+(?:TABLE|COLUMN)\\s+)"
    );

    @Test
    void v18AddsReconnectPositionColumnsWithSafeDefaults() throws Exception {
        URI resource = getClass().getClassLoader()
                .getResource("db/migration/V18__playback_position.sql").toURI();
        String sql = Files.readString(Paths.get(resource));

        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS position_ms");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS seek_sequence");
        assertThat(sql).contains("DEFAULT 0");
        assertThat(sql).doesNotMatch(DESTRUCTIVE_STATEMENT);
    }
}
