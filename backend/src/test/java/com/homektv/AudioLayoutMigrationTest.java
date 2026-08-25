package com.homektv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AudioLayoutMigrationTest {

    private static final Pattern DESTRUCTIVE_STATEMENT = Pattern.compile(
            "(?im)^\\s*(DELETE\\s+FROM|TRUNCATE\\s+(?:TABLE\\s+)?|DROP\\s+(?:TABLE|COLUMN)\\s+)"
    );

    @Test
    void v17AddsOnlyBackwardCompatibleAudioLayoutColumns() throws Exception {
        String sql = Files.readString(migrationPath());

        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS audio_layout");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS original_track_index");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS accompaniment_track_index");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS original_channel");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS accompaniment_channel");
        assertThat(sql).contains("DEFAULT 'NORMAL_STEREO'");
        assertThat(sql).contains("DEFAULT 'LEFT'");
        assertThat(sql).contains("DEFAULT 'RIGHT'");
        assertThat(sql).doesNotMatch(DESTRUCTIVE_STATEMENT);
    }

    @Test
    void legacyTwoTrackRowsAreBackfilledAsDualTrackWithoutTouchingFiles() throws Exception {
        String sql = Files.readString(migrationPath());

        assertThat(sql).contains("audio_tracks >= 2");
        assertThat(sql).contains("DUAL_TRACK");
        assertThat(sql).contains("vocal_track_index");
        assertThat(sql).contains("vocal_track_index = COALESCE(vocal_track_index, 1)");
        assertThat(sql).doesNotContain("Files.", "DELETE FROM", "DROP COLUMN");
    }

    private Path migrationPath() throws Exception {
        URI resource = getClass().getClassLoader().getResource("db/migration/V17__audio_layout.sql").toURI();
        return Paths.get(resource);
    }
}
