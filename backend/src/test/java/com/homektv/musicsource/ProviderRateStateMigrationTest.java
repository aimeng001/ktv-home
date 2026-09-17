package com.homektv.musicsource;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRateStateMigrationTest {
    @Test
    void migrationCreatesPersistentRowsForEveryKnownProviderWithoutDestructiveSql() throws Exception {
        Path migration = Path.of(getClass().getClassLoader()
                .getResource("db/migration/V44__music_provider_rate_state.sql").toURI());
        String sql = Files.readString(migration).toLowerCase();

        assertThat(sql).contains("create table music_provider_rate_state");
        assertThat(sql).contains("next_request_at").contains("cooldown_until")
                .contains("window_date_utc").contains("request_count")
                .contains("failure_streak").contains("on conflict (provider) do nothing");
        assertThat(sql).contains("('netease'), ('qq'), ('kugou')");
        assertThat(sql).doesNotContain("drop table").doesNotContain("delete from")
                .doesNotContain("truncate ");
    }
}
