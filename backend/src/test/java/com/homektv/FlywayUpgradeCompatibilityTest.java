package com.homektv;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
class FlywayUpgradeCompatibilityTest {

    private static final int LEGACY_V24_CHECKSUM = 1338543542;
    private static final int CURRENT_V24_CHECKSUM = -1429305876;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv_upgrade_test")
            .withUsername("ktv")
            .withPassword("ktv");

    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;");
        }
    }

    @Test
    void unconfiguredFlywayFailsWhenDatabaseHasLegacyV24Checksum() throws Exception {
        String dbUrl = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        setupLegacyV24SchemaHistory(dbUrl, user, pass, LEGACY_V24_CHECKSUM);

        // 默认严格模式（未配置 24:mismatched）
        Flyway strictFlyway = Flyway.configure()
                .dataSource(dbUrl, user, pass)
                .load();

        // 必须稳定复现老库升级时的致命异常 (RED)
        assertThatThrownBy(strictFlyway::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("Migration checksum mismatch for migration version 24")
                .hasMessageContaining(String.valueOf(LEGACY_V24_CHECKSUM))
                .hasMessageContaining(String.valueOf(CURRENT_V24_CHECKSUM));
    }

    @Test
    void validateOnMigrateFalseBypassesChecksumMismatch() throws Exception {
        String dbUrl = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        setupLegacyV24SchemaHistory(dbUrl, user, pass, LEGACY_V24_CHECKSUM);

        // 当 validateOnMigrate=false 时，即使存在 checksum mismatch，也能正常 migrate
        Flyway tolerantFlyway = Flyway.configure()
                .dataSource(dbUrl, user, pass)
                .validateOnMigrate(false)
                .target("24")
                .load();

        assertThatCode(tolerantFlyway::migrate).doesNotThrowAnyException();
    }

    @Test
    void flywayRepairThenMigrateSucceedsOnLegacyDatabase() throws Exception {
        String dbUrl = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        setupLegacyV24SchemaHistory(dbUrl, user, pass, LEGACY_V24_CHECKSUM);

        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, user, pass)
                .target("25") // 验证能够修复 V24 并向下迁移至 V25
                .load();

        // 模拟 FlywayMigrationStrategy: 先 repair 修复历史校验和，再执行 migrate
        assertThatCode(() -> {
            flyway.repair();
            flyway.migrate();
        }).doesNotThrowAnyException();
    }

    @Test
    void failedPostgresMigrationRollsBackEveryStatementInThatMigration(@TempDir Path tempDir) throws Exception {
        Path migrationDirectory = Files.createDirectories(tempDir.resolve("migration"));
        Files.writeString(migrationDirectory.resolve("V1__stable_data.sql"), """
                CREATE TABLE stable_data(id INTEGER PRIMARY KEY);
                INSERT INTO stable_data(id) VALUES (1);
                """, StandardCharsets.UTF_8);
        Files.writeString(migrationDirectory.resolve("V2__partially_applied.sql"), """
                CREATE TABLE partial_data(id INTEGER PRIMARY KEY);
                INSERT INTO partial_data(id) VALUES (2);
                INSERT INTO stable_data(id) VALUES (1);
                """, StandardCharsets.UTF_8);

        String location = "filesystem:" + migrationDirectory.toAbsolutePath().toString().replace('\\', '/');
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(location)
                .table("flyway_rollback_history")
                .load();

        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("stable_data");

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM stable_data")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var rs = stmt.executeQuery("SELECT to_regclass('public.partial_data')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }
            try (var rs = stmt.executeQuery(
                    "SELECT success FROM flyway_rollback_history WHERE version='1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    private void setupLegacyV24SchemaHistory(String jdbcUrl, String username, String password, int legacyChecksum)
            throws Exception {
        // 先迁移到 V23，使前面所有表结构与基础历史记录准备就绪
        Flyway baseFlyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .target("23")
                .load();
        baseFlyway.migrate();

        // 模拟老库中由 v1.0.16 成功执行了 V24 的真实数据库状态
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS song_artists (
                        id BIGSERIAL PRIMARY KEY,
                        song_id BIGINT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                        artist_name VARCHAR(128) NOT NULL,
                        artist_key VARCHAR(128) NOT NULL,
                        artist_order INT NOT NULL DEFAULT 0,
                        artist_py VARCHAR(256) NOT NULL DEFAULT '',
                        artist_init VARCHAR(64) NOT NULL DEFAULT '',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_song_artist_unique UNIQUE (song_id, artist_key)
                    );
                    CREATE INDEX IF NOT EXISTS idx_song_artists_key ON song_artists (artist_key);
                    CREATE INDEX IF NOT EXISTS idx_song_artists_py ON song_artists (artist_init, artist_py);
                """);
            }

            String insertSql = """
                INSERT INTO flyway_schema_history
                (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
                VALUES (24, '24', 'song artists', 'SQL', 'V24__song_artists.sql', ?, 'ktv', now(), 10, true)
                ON CONFLICT (installed_rank) DO UPDATE SET checksum = EXCLUDED.checksum;
            """;
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setInt(1, legacyChecksum);
                pstmt.executeUpdate();
            }
        }
    }
}
