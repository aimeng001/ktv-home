package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetCleanupServiceTest {
    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AssetCleanupService cleanup;
    private Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:asset-cleanup-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE songs(cover_path VARCHAR(500), lyric_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE playlists(cover_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE artist_profiles(avatar_path VARCHAR(500))");
        jdbc.execute("CREATE TABLE settings(key VARCHAR(100) PRIMARY KEY, value VARCHAR(1000))");
        jdbc.execute("CREATE TABLE transaction_probe(id INT)");

        dataRoot = Files.createDirectories(tempDir.resolve("data"));
        AppProperties props = new AppProperties();
        props.setDataPath(dataRoot.toString());
        props.setKtvLibraryPath(tempDir.resolve("music").toString());
        props.setSourceLibraryPath(tempDir.resolve("source").toString());
        props.setLibraryMode(LibraryMode.MANAGED);
        cleanup = new AssetCleanupService(jdbc, new AssetWriter(props), new ObjectMapper());
    }

    @Test
    void deletesUnreferencedApplicationAsset() throws Exception {
        Path file = writeAsset("playlist-covers/1-old.jpg");

        cleanup.deleteIfUnreferenced("playlist-covers/1-old.jpg");

        assertThat(file).doesNotExist();
    }

    @Test
    void preservesAssetReferencedBySongOrPlaylist() throws Exception {
        Path songAsset = writeAsset("covers/song.jpg");
        Path playlistAsset = writeAsset("playlist-covers/1.jpg");
        jdbc.update("INSERT INTO songs(cover_path,lyric_path) VALUES (?,?)", "covers/song.jpg", "lyrics/unused.lrc");
        jdbc.update("INSERT INTO playlists(cover_path) VALUES (?)", "playlist-covers/1.jpg");

        cleanup.deleteIfUnreferenced("covers/song.jpg");
        cleanup.deleteIfUnreferenced("playlist-covers/1.jpg");

        assertThat(songAsset).exists();
        assertThat(playlistAsset).exists();
    }

    @Test
    void preservesTheCurrentStandbyLogo() throws Exception {
        Path file = writeAsset("standby/logo-current.png");
        jdbc.update("INSERT INTO settings(key,value) VALUES ('standby_logo_path',?)",
                new ObjectMapper().writeValueAsString("standby/logo-current.png"));

        cleanup.deleteIfUnreferenced("standby/logo-current.png");

        assertThat(file).exists();
    }

    @Test
    void preservesDirectlyReferencedArtistAvatar() throws Exception {
        Path file = writeAsset("artist-covers/current.jpg");
        jdbc.update("INSERT INTO artist_profiles(avatar_path) VALUES (?)", "artist-covers/current.jpg");

        cleanup.deleteIfUnreferenced("artist-covers/current.jpg");

        assertThat(file).exists();
    }

    @Test
    void defersCleanupUntilTransactionCommits() throws Exception {
        Path file = writeAsset("playlist-covers/1-old.jpg");

        transactions.executeWithoutResult(status -> cleanup.afterCommitIfUnreferenced("playlist-covers/1-old.jpg"));

        assertThat(file).doesNotExist();
    }

    @Test
    void sweepsOnlyOldUnreferencedAssetsInKnownDirectories() throws Exception {
        Path orphan = writeAsset("covers/orphan.jpg");
        Path referenced = writeAsset("covers/referenced.jpg");
        Path fresh = writeAsset("covers/fresh.jpg");
        Path unknown = writeAsset("private/keep.dat");
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(orphan, old);
        Files.setLastModifiedTime(referenced, old);
        Files.setLastModifiedTime(unknown, old);
        jdbc.update("INSERT INTO songs(cover_path) VALUES (?)", "covers/referenced.jpg");

        assertThat(cleanup.sweepOrphans()).isEqualTo(1);
        assertThat(orphan).doesNotExist();
        assertThat(referenced).exists();
        assertThat(fresh).exists();
        assertThat(unknown).exists();
    }

    @Test
    void sweepInsideExistingTransactionFailsClosedWithoutCommittingCallerChanges() throws Exception {
        Path orphan = writeOldAsset("covers/transactional-orphan.jpg");

        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO transaction_probe(id) VALUES (1)");
            assertThat(cleanup.sweepOrphans()).isZero();
            status.setRollbackOnly();
        });

        assertThat(orphan).exists();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_probe", Integer.class)).isZero();
    }

    @Test
    void preservesReferencedArtistAvatarDuringSweep() throws Exception {
        Path avatar = writeAsset("artist-covers/referenced.jpg");
        Files.setLastModifiedTime(avatar, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        jdbc.update("INSERT INTO artist_profiles(avatar_path) VALUES (?)", "artist-covers/referenced.jpg");

        assertThat(cleanup.sweepOrphans()).isZero();
        assertThat(avatar).exists();
    }

    @Test
    void preservesEverySupportedReferenceTypeDuringSweep() throws Exception {
        Path cover = writeOldAsset("covers/song.jpg");
        Path lyric = writeOldAsset("lyrics/song.lrc");
        Path playlistCover = writeOldAsset("playlist-covers/list.jpg");
        Path artistAvatar = writeOldAsset("artist-covers/artist.jpg");
        Path standbyLogo = writeOldAsset("standby/logo.png");
        Path orphan = writeOldAsset("covers/orphan.jpg");
        jdbc.update("INSERT INTO songs(cover_path,lyric_path) VALUES (?,?)", "covers/song.jpg", "lyrics/song.lrc");
        jdbc.update("INSERT INTO playlists(cover_path) VALUES (?)", "playlist-covers/list.jpg");
        jdbc.update("INSERT INTO artist_profiles(avatar_path) VALUES (?)", "artist-covers/artist.jpg");
        jdbc.update("INSERT INTO settings(key,value) VALUES ('standby_logo_path',?)",
                new ObjectMapper().writeValueAsString("standby/logo.png"));

        assertThat(cleanup.sweepOrphans()).isEqualTo(1);
        assertThat(cover).exists();
        assertThat(lyric).exists();
        assertThat(playlistCover).exists();
        assertThat(artistAvatar).exists();
        assertThat(standbyLogo).exists();
        assertThat(orphan).doesNotExist();
    }

    @Test
    void sweepsOrphansWhenDistinctAssetReferencesExceedTheOldLimit() throws Exception {
        Path orphan = writeAsset("covers/orphan.jpg");
        Path referenced = writeAsset("covers/referenced.jpg");
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(orphan, old);
        Files.setLastModifiedTime(referenced, old);
        jdbc.update("INSERT INTO songs(cover_path) VALUES (?)", "covers/referenced.jpg");

        String sql = "INSERT INTO songs(cover_path) VALUES (?)";
        for (int first = 0; first < 100_000; first += 1_000) {
            List<Object[]> rows = new ArrayList<>(1_000);
            for (int offset = 0; offset < 1_000; offset++) {
                rows.add(new Object[]{"covers/reference-" + (first + offset) + ".jpg"});
            }
            jdbc.batchUpdate(sql, rows);
        }

        assertThat(cleanup.sweepOrphans()).isEqualTo(1);
        assertThat(orphan).doesNotExist();
        assertThat(referenced).exists();
    }

    private Path writeAsset(String relativePath) throws Exception {
        Path file = dataRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        return file;
    }

    private Path writeOldAsset(String relativePath) throws Exception {
        Path file = writeAsset(relativePath);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        return file;
    }
}
