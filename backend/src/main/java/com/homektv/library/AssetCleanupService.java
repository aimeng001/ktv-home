package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class AssetCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AssetCleanupService.class);
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    private static final List<String> APPLICATION_ASSET_DIRECTORIES = List.of(
            "covers", "lyrics", "artist-covers", "playlist-covers", "standby");
    static final int SWEEP_BATCH_SIZE = 256;

    private final JdbcTemplate jdbc;
    private final AssetWriter assetWriter;
    private final ObjectMapper mapper;

    public AssetCleanupService(JdbcTemplate jdbc, AssetWriter assetWriter, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.assetWriter = assetWriter;
        this.mapper = mapper;
    }

    public void afterCommitIfUnreferenced(String... relativePaths) {
        String[] paths = Arrays.stream(relativePaths == null ? new String[0] : relativePaths)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toArray(String[]::new);
        if (paths.length == 0) return;

        Runnable cleanup = () -> Arrays.stream(paths).forEach(this::deleteIfUnreferenced);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    public void deleteIfUnreferenced(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return;
        try {
            if (isReferenced(relativePath)) return;
            assetWriter.deleteReadableCache(relativePath);
        } catch (RuntimeException failure) {
            // Cleanup must not turn an already committed business transaction into a failure.
            log.warn("应用资产清理检查失败：{} - {}", relativePath, failure.getMessage());
        }
    }

    public int sweepOrphans() {
        int[] deleted = {0};
        try {
            Integer result = jdbc.execute((ConnectionCallback<Integer>) connection ->
                    sweepOrphansInTransaction(connection, deleted));
            return result == null ? deleted[0] : result;
        } catch (RuntimeException failure) {
            log.error("资产引用快照或孤儿清理失败", failure);
            return deleted[0];
        }
    }

    private int sweepOrphansInTransaction(Connection connection, int[] deleted) throws SQLException {
        // This task owns its temp-table DDL and filesystem deletes; never run it inside a caller's transaction.
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("孤儿资产清理必须在独立事务中运行");
        }
        String temporaryTable = "ktv_asset_cleanup_refs_"
                + UUID.randomUUID().toString().replace("-", "");
        connection.setAutoCommit(false);
        try {
            createTemporaryReferenceSnapshot(connection, temporaryTable);
            Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
            for (String directory : APPLICATION_ASSET_DIRECTORIES) {
                sweepDirectory(connection, temporaryTable, assetWriter.dataRootForCleanup(), directory,
                        cutoff, deleted);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + temporaryTable);
            }
            connection.commit();
            return deleted[0];
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + temporaryTable);
            } catch (SQLException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException resetFailure) {
                log.warn("资产清理结束后无法恢复数据库连接状态：{}", resetFailure.getMessage());
            }
        }
    }

    private void createTemporaryReferenceSnapshot(Connection connection, String temporaryTable)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE " + temporaryTable
                    + " (path TEXT PRIMARY KEY) ON COMMIT DROP");
            statement.executeUpdate("""
                    INSERT INTO %s(path)
                    SELECT DISTINCT asset_path
                    FROM (
                        SELECT cover_path AS asset_path FROM songs WHERE cover_path IS NOT NULL
                        UNION ALL
                        SELECT lyric_path FROM songs WHERE lyric_path IS NOT NULL
                        UNION ALL
                        SELECT cover_path FROM playlists WHERE cover_path IS NOT NULL
                        UNION ALL
                        SELECT avatar_path FROM artist_profiles WHERE avatar_path IS NOT NULL
                    ) referenced_assets
                    WHERE asset_path IS NOT NULL AND TRIM(asset_path) <> ''
                    """.formatted(temporaryTable));
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CAST(value AS TEXT) FROM settings
                WHERE key = 'standby_logo_path'
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final String path;
                try {
                    path = mapper.readValue(rows.getString(1), String.class);
                } catch (Exception failure) {
                    throw new IllegalStateException("待机 Logo 引用无法解析", failure);
                }
                if (path == null || path.isBlank()) continue;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO %s(path)
                        SELECT ? WHERE NOT EXISTS (
                            SELECT 1 FROM %s WHERE path = ?
                        )
                        """.formatted(temporaryTable, temporaryTable))) {
                    insert.setString(1, path);
                    insert.setString(2, path);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void sweepDirectory(Connection connection, String temporaryTable, Path dataRoot,
                                String directory, Instant cutoff, int[] deleted) throws SQLException {
        Path root = dataRoot.resolve(directory).normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) return;

        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.iterator();
            List<String> candidates = new ArrayList<>(SWEEP_BATCH_SIZE);
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!isOldRegularFile(path, cutoff)) continue;
                candidates.add(dataRoot.relativize(path).toString()
                        .replace(path.getFileSystem().getSeparator(), "/"));
                if (candidates.size() == SWEEP_BATCH_SIZE) {
                    deleted[0] += deleteUnreferencedBatch(connection, temporaryTable, candidates);
                    candidates.clear();
                }
            }
            if (!candidates.isEmpty()) {
                deleted[0] += deleteUnreferencedBatch(connection, temporaryTable, candidates);
            }
        } catch (IOException | UncheckedIOException failure) {
            log.warn("应用资产孤儿扫描失败：{} - {}", root, failure.getMessage());
        }
    }

    private int deleteUnreferencedBatch(Connection connection, String temporaryTable,
                                        List<String> candidates) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        Set<String> referenced = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT path FROM " + temporaryTable + " WHERE path IN (" + placeholders + ")")) {
            for (int index = 0; index < candidates.size(); index++) {
                statement.setString(index + 1, candidates.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) referenced.add(rows.getString(1));
            }
        }

        int deleted = 0;
        for (String candidate : candidates) {
            if (referenced.contains(candidate)) continue;
            assetWriter.deleteReadableCache(candidate);
            deleted++;
        }
        return deleted;
    }

    @Scheduled(fixedDelayString = "${home-ktv.asset-cleanup.interval-ms:3600000}")
    public void scheduledSweep() {
        sweepOrphans();
    }

    private boolean isOldRegularFile(Path path, Instant cutoff) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && attributes.lastModifiedTime().toInstant().isBefore(cutoff);
        } catch (Exception failure) {
            return false;
        }
    }

    private boolean isReferenced(String relativePath) {
        String jsonPath;
        try {
            jsonPath = mapper.writeValueAsString(relativePath);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化待机 Logo 路径", failure);
        }
        Boolean referenced = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM songs
                    WHERE cover_path = ? OR lyric_path = ?
                    UNION ALL
                    SELECT 1 FROM playlists
                    WHERE cover_path = ?
                    UNION ALL
                    SELECT 1 FROM settings
                    WHERE key = 'standby_logo_path' AND CAST(value AS TEXT) = ?
                    UNION ALL
                    SELECT 1 FROM artist_profiles
                    WHERE avatar_path = ?
                )
                """, Boolean.class, relativePath, relativePath, relativePath, jsonPath, relativePath);
        return Boolean.TRUE.equals(referenced);
    }
}
