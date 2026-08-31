package com.homektv.repo;

import com.homektv.domain.SongFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** 歌曲文件数据访问层，操作 song_file 表。
 *
 * Data access layer for song files, operating on the song_file table.
 */
public interface SongFileRepository extends JpaRepository<SongFile, Long> {

    List<SongFile> findBySongIdOrderByPriorityDesc(Long songId);

    Optional<SongFile> findByFilePath(String filePath);
    List<SongFile> findBySongIdAndValidTrueOrderByPriorityDesc(Long songId);
    List<SongFile> findBySongIdInAndValidTrueOrderByPriorityDesc(Collection<Long> songIds);

    /**
     * Returns whether a song has at least one probed, valid media source.
     * Fast Index rows intentionally do not satisfy this query until FFprobe
     * has persisted a concrete media type.
     */
    @Query("""
            SELECT CASE WHEN COUNT(file.id) > 0 THEN true ELSE false END
            FROM SongFile file
            WHERE file.songId = :songId
              AND file.valid = true
              AND file.probePending = false
              AND file.mediaType IS NOT NULL
              AND TRIM(file.mediaType) <> ''
              AND LOWER(TRIM(file.mediaType)) <> 'pending_probe'
            """)
    boolean existsReadyFile(@Param("songId") Long songId);

    boolean existsBySourceMd5(String sourceMd5);
    boolean existsByOutputMd5(String outputMd5);
    List<SongFile> findByFileRoleOrderByImportedAtDesc(String fileRole);
    List<SongFile> findByFileRoleAndFilePathIn(String fileRole, Collection<String> filePaths);
    List<SongFile> findByFileRoleAndRelativePathIn(String fileRole, Collection<String> relativePaths);
    Slice<SongFile> findByFileRoleAndFilePathGreaterThanOrderByFilePath(
            String fileRole, String filePath, Pageable pageable);
    @Query("""
            SELECT file.id AS id, file.filePath AS filePath, file.probePending AS probePending
            FROM SongFile file
            WHERE file.fileRole = :fileRole AND file.filePath > :filePath
            ORDER BY file.filePath
            """)
    Slice<SongFileScanSnapshot> findScanSnapshotsByFileRoleAndFilePathGreaterThanOrderByFilePath(
            @Param("fileRole") String fileRole, @Param("filePath") String filePath, Pageable pageable);
    @Query(value = """
            SELECT file.*
            FROM song_files file
            WHERE file.file_role = :fileRole
              AND file.probe_pending = TRUE
              AND file.file_path > :filePath
              AND (
                    file.id <= :maxId
                    OR EXISTS (
                        SELECT 1
                        FROM library_scan_seen_paths seen
                        WHERE seen.scan_id = :scanId
                          AND seen.file_role = file.file_role
                          AND seen.file_path = file.file_path
                    )
              )
            ORDER BY file.file_path
            """, nativeQuery = true)
    Slice<SongFile> findPendingForScan(@Param("fileRole") String fileRole,
                                      @Param("maxId") Long maxId,
                                      @Param("scanId") UUID scanId,
                                      @Param("filePath") String filePath,
                                      Pageable pageable);
    long countByFileRoleAndProbePendingTrue(String fileRole);
    @Query("SELECT COALESCE(MAX(file.id), 0) FROM SongFile file WHERE file.fileRole = :fileRole")
    Long findMaxIdByFileRole(@Param("fileRole") String fileRole);
    @Query(value = """
            SELECT COUNT(*)
            FROM song_files file
            WHERE file.file_role = :fileRole
              AND file.valid = TRUE
              AND (
                    file.file_path = :root
                    OR LEFT(file.file_path, LENGTH(:backslashPrefix)) = :backslashPrefix
                    OR LEFT(file.file_path, LENGTH(:slashPrefix)) = :slashPrefix
              )
            """, nativeQuery = true)
    long countValidByFileRoleAndRoot(@Param("fileRole") String fileRole,
                                     @Param("root") String root,
                                     @Param("backslashPrefix") String backslashPrefix,
                                     @Param("slashPrefix") String slashPrefix);
    List<SongFile> findBySourcePath(String sourcePath);

    /** 伴奏轨判定为低置信度的文件源，供后台人工复核（详设§11：入库记伴奏轨，判不准的挑出来核对）。
     *
     * Files whose vocal track confidence is low, queued for manual review
     * (per detailed design §11: record accompaniment tracks on ingestion,
     * flag uncertain ones for verification).
     */
    Page<SongFile> findByVocalConfidence(String vocalConfidence, Pageable pageable);
}
