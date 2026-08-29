package com.homektv.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL-backed scan reconciliation store. */
@Component
public class JdbcLibraryScanSeenPathStore implements LibraryScanSeenPathStore {
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    public JdbcLibraryScanSeenPathStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordBatch(UUID scanId, String fileRole, Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;
        List<String> paths = filePaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (paths.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO library_scan_seen_paths (scan_id, file_role, file_path, pending_at_start) "
                        + "VALUES (?, ?, ?, FALSE) "
                        + "ON CONFLICT (scan_id, file_role, file_path) DO NOTHING",
                paths,
                BATCH_SIZE,
                (statement, path) -> {
                    statement.setObject(1, scanId);
                    statement.setString(2, fileRole);
                    statement.setString(3, path);
                });
    }

    @Override
    public void recordPendingBatch(UUID scanId, String fileRole, Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;
        List<String> paths = filePaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (paths.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO library_scan_seen_paths (scan_id, file_role, file_path, pending_at_start) "
                        + "VALUES (?, ?, ?, TRUE) "
                        + "ON CONFLICT (scan_id, file_role, file_path) DO UPDATE "
                        + "SET pending_at_start = TRUE",
                paths,
                BATCH_SIZE,
                (statement, path) -> {
                    statement.setObject(1, scanId);
                    statement.setString(2, fileRole);
                    statement.setString(3, path);
                });
    }

    @Override
    public Set<String> findPendingAtStart(UUID scanId, String fileRole, Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return Set.of();
        List<String> paths = filePaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (paths.isEmpty()) return Set.of();
        Set<String> result = new HashSet<>();
        for (int offset = 0; offset < paths.size(); offset += BATCH_SIZE) {
            List<String> page = paths.subList(offset, Math.min(offset + BATCH_SIZE, paths.size()));
            String placeholders = String.join(",", Collections.nCopies(page.size(), "?"));
            String sql = "SELECT file_path FROM library_scan_seen_paths "
                    + "WHERE scan_id = ? AND file_role = ? AND pending_at_start = TRUE "
                    + "AND file_path IN (" + placeholders + ")";
            List<Object> args = new ArrayList<>(2 + page.size());
            args.add(scanId);
            args.add(fileRole);
            args.addAll(page);
            result.addAll(jdbc.query(sql, args.toArray(),
                    (resultSet, rowNum) -> resultSet.getString(1)));
        }
        return result;
    }

    @Override
    public MissingFiles markMissing(UUID scanId, String fileRole, String activeRoot) {
        String root = activeRoot == null ? "" : activeRoot;
        String backslashPrefix = root.endsWith("\\") || root.endsWith("/") ? root : root + "\\";
        String slashPrefix = root.endsWith("/") || root.endsWith("\\") ? root : root + "/";
        List<Long> songIds = jdbc.query(
                "UPDATE song_files file SET valid = FALSE "
                        + "WHERE file.file_role = ? AND file.valid = TRUE "
                        + "AND (file.file_path = ? "
                        + "  OR LEFT(file.file_path, LENGTH(?)) = ? "
                        + "  OR LEFT(file.file_path, LENGTH(?)) = ?) "
                        + "AND NOT EXISTS ("
                        + "  SELECT 1 FROM library_scan_seen_paths seen "
                        + "  WHERE seen.scan_id = ? AND seen.file_role = file.file_role "
                        + "    AND seen.file_path = file.file_path"
                        + ") RETURNING file.song_id",
                statement -> {
                    statement.setString(1, fileRole);
                    statement.setString(2, root);
                    statement.setString(3, backslashPrefix);
                    statement.setString(4, backslashPrefix);
                    statement.setString(5, slashPrefix);
                    statement.setString(6, slashPrefix);
                    statement.setObject(7, scanId);
                },
                (resultSet, rowNum) -> resultSet.getLong(1));
        return new MissingFiles(songIds.size(), songIds);
    }

    @Override
    public void delete(UUID scanId) {
        jdbc.update("DELETE FROM library_scan_seen_paths WHERE scan_id = ?", scanId);
    }
}
