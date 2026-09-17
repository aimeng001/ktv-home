package com.homektv;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class FlywaySpringBootUpgradeIntegrationTest {

    private static final int LEGACY_V24_CHECKSUM = 1338543542;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv_spring_upgrade_test")
            .withUsername("ktv")
            .withPassword("ktv");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        postgres.start();
        String jdbcUrl = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        // 在 Spring 上下文初始化 Flyway 之前，先模拟老库执行至 V24 (持有旧 checksum)
        setupLegacyDatabase(jdbcUrl, user, pass);

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void springBootContextStartsSuccessfullyOnLegacyDatabase() {
        // 验证 Spring Boot 上下文成功加载，未被 FlywayValidateException 阻断
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBean("flywayMigrationStrategy")).isTrue();
    }

    private static void setupLegacyDatabase(String jdbcUrl, String username, String password) throws Exception {
        // 先迁移到 V23
        Flyway baseFlyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .target("23")
                .load();
        baseFlyway.migrate();

        // 模拟老库已执行 V24
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
                pstmt.setInt(1, LEGACY_V24_CHECKSUM);
                pstmt.executeUpdate();
            }
        }
    }
}
