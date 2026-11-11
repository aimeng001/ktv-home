package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetCleanupResourceBudgetTest {
    private static final int DISTINCT_REFERENCE_COUNT = 200_001;

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private AssetCleanupService cleanup;
    private Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:asset-cleanup-budget-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        dataRoot = Files.createDirectories(tempDir.resolve("data"));
        AppProperties props = new AppProperties();
        props.setDataPath(dataRoot.toString());
        props.setKtvLibraryPath(tempDir.resolve("music").toString());
        props.setSourceLibraryPath(tempDir.resolve("source").toString());
        props.setLibraryMode(LibraryMode.MANAGED);
        cleanup = new AssetCleanupService(jdbc, new AssetWriter(props), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void cleansOrphansWithMoreThanTwoHundredThousandDistinctReferences() throws Exception {
        Path orphan = writeOldAsset("covers/orphan.jpg");
        Path referenced = writeOldAsset("covers/referenced.jpg");
        jdbc.update("INSERT INTO songs(cover_path) VALUES (?)", "covers/referenced.jpg");
        insertDistinctReferences(DISTINCT_REFERENCE_COUNT - 1);

        assertThat(cleanup.sweepOrphans()).isEqualTo(1);
        assertThat(orphan).doesNotExist();
        assertThat(referenced).exists();
        assertSnapshotTableWasDroppedAndConnectionWasRestored();
    }

    @Test
    void keepsExpiredCandidatesWhenReferenceSnapshotCannotBeBuilt() throws Exception {
        Path orphan = writeOldAsset("covers/orphan.jpg");
        jdbc.execute("DROP TABLE artist_profiles");

        assertThat(cleanup.sweepOrphans()).isZero();
        assertThat(orphan).exists();
        assertSnapshotTableWasDroppedAndConnectionWasRestored();
    }

    @Test
    void keepsExpiredCandidatesWhenStandbyLogoReferenceIsInvalid() throws Exception {
        Path orphan = writeOldAsset("covers/orphan.jpg");
        jdbc.update("INSERT INTO settings(key,value) VALUES ('standby_logo_path',?)", "{invalid-json");

        assertThat(cleanup.sweepOrphans()).isZero();
        assertThat(orphan).exists();
        assertSnapshotTableWasDroppedAndConnectionWasRestored();
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE songs(cover_path VARCHAR(500), lyric_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE playlists(cover_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE artist_profiles(avatar_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE settings(key VARCHAR(100) PRIMARY KEY, value VARCHAR(1000))");
    }

    private void insertDistinctReferences(int count) {
        String sql = "INSERT INTO songs(cover_path) VALUES (?)";
        for (int first = 0; first < count; first += 1_000) {
            int batchSize = Math.min(1_000, count - first);
            List<Object[]> rows = new ArrayList<>(batchSize);
            for (int offset = 0; offset < batchSize; offset++) {
                rows.add(new Object[]{"covers/budget-reference-" + (first + offset) + ".jpg"});
            }
            jdbc.batchUpdate(sql, rows);
        }
    }

    private Path writeOldAsset(String relativePath) throws Exception {
        Path file = dataRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        return file;
    }

    private void assertSnapshotTableWasDroppedAndConnectionWasRestored() {
        Integer remainingTables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE LOWER(TABLE_NAME) LIKE 'ktv_asset_cleanup_refs_%'
                """, Integer.class);
        Boolean autoCommit = jdbc.execute((ConnectionCallback<Boolean>) java.sql.Connection::getAutoCommit);

        assertThat(remainingTables).isZero();
        assertThat(autoCommit).isTrue();
    }
}
