package com.homektv.library;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the production migration path from the last pre-IKTV schema. */
@EnabledIfEnvironmentVariable(
        named = "HOMEKTV_TEST_PG_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+")
class IktvMigrationUpgradeIntegrationTest {
    @Test
    void upgradesVersion52ToLatestAndAcceptsTenDigitCatalogNumbers() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("52")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(latestVersion(jdbc)).isEqualTo("52");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(latestVersion(jdbc)).isEqualTo("57");
        assertThat(jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'songs' AND column_name = 'catalog_number'
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iktv_reference_fusion_control WHERE singleton_id = 1",
                Integer.class)).isEqualTo(1);

        long songId = jdbc.queryForObject("""
                INSERT INTO songs (title, artist, media_type, fingerprint)
                VALUES ('Ten digit migration check', 'Migration test', 'AUDIO', 'iktv-v52-upgrade-check')
                RETURNING id
                """, Long.class);
        jdbc.update("UPDATE songs SET catalog_number = ? WHERE id = ?", "1000000750", songId);
        assertThat(jdbc.queryForObject("SELECT catalog_number FROM songs WHERE id = ?", String.class, songId))
                .isEqualTo("1000000750");
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(required("HOMEKTV_TEST_PG_URL"));
        dataSource.setUser(required("HOMEKTV_TEST_PG_USER"));
        dataSource.setPassword(required("HOMEKTV_TEST_PG_PASSWORD"));
        return dataSource;
    }

    private String latestVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }
}
