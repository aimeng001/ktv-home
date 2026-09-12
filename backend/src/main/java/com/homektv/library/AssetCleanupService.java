package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class AssetCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AssetCleanupService.class);
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    private static final List<String> APPLICATION_ASSET_DIRECTORIES = List.of(
            "covers", "lyrics", "artist-covers", "playlist-covers", "standby");
    static final int MAX_REFERENCED_PATHS = 100_000;

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
        final Set<String> referenced;
        try {
            referenced = referencedPaths();
        } catch (RuntimeException failure) {
            log.error("资产引用集合不完整，终止孤儿删除", failure);
            return 0;
        }
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        Path dataRoot = assetWriter.dataRootForCleanup();
        int[] deleted = {0};

        for (String directory : APPLICATION_ASSET_DIRECTORIES) {
            Path root = dataRoot.resolve(directory).normalize();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> isOldRegularFile(path, cutoff))
                        .forEach(path -> {
                            String relative = dataRoot.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                            if (!referenced.contains(relative)) {
                                assetWriter.deleteReadableCache(relative);
                                deleted[0]++;
                            }
                        });
            } catch (Exception failure) {
                log.warn("应用资产孤儿扫描失败：{} - {}", root, failure.getMessage());
            }
        }
        return deleted[0];
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

    private Set<String> referencedPaths() {
        Set<String> paths = new HashSet<>();
        jdbc.query("""
                SELECT cover_path FROM songs WHERE cover_path IS NOT NULL
                UNION
                SELECT lyric_path FROM songs WHERE lyric_path IS NOT NULL
                UNION
                SELECT cover_path FROM playlists WHERE cover_path IS NOT NULL
                UNION
                SELECT avatar_path FROM artist_profiles WHERE avatar_path IS NOT NULL
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) addReference(paths, rs.getString(1));
                    return null;
                });
        jdbc.query("""
                SELECT CAST(value AS TEXT) FROM settings
                WHERE key = 'standby_logo_path'
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        String value = rs.getString(1);
                        try {
                            addReference(paths, mapper.readValue(value, String.class));
                        } catch (Exception failure) {
                            throw new IllegalStateException("待机 Logo 引用无法解析", failure);
                        }
                    }
                    return null;
                });
        return paths;
    }

    private void addReference(Set<String> paths, String value) {
        if (value == null || value.isBlank() || paths.contains(value)) return;
        if (paths.size() >= MAX_REFERENCED_PATHS) {
            throw new IllegalStateException("资产引用数量超过安全上限");
        }
        paths.add(value);
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
