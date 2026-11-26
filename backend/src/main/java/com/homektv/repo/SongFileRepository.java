package com.homektv.repo;

import com.homektv.domain.SongFile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Batch equivalent of existsReadyFile for queue advancement. Keeping the
     * readiness predicate in SQL avoids one file query per waiting item.
     */
    @Query("""
            SELECT DISTINCT file.songId
            FROM SongFile file
            WHERE file.songId IN :songIds
              AND file.valid = true
              AND file.probePending = false
              AND file.mediaType IS NOT NULL
              AND TRIM(file.mediaType) <> ''
              AND LOWER(TRIM(file.mediaType)) <> 'pending_probe'
            """)
    java.util.Set<Long> findSongIdsWithReadyFile(@Param("songIds") java.util.Collection<Long> songIds);
    boolean existsBySourceMd5(String sourceMd5);
    boolean existsByOutputMd5(String outputMd5);
    Optional<SongFile> findByFileRoleAndFilePath(String fileRole, String filePath);
    List<SongFile> findByFileRoleAndFilePathIn(String fileRole, Collection<String> filePaths);
    List<SongFile> findByFileRoleAndRelativePathIn(String fileRole, Collection<String> relativePaths);
    Slice<SongFile> findByFileRoleAndFilePathGreaterThanOrderByFilePath(
            String fileRole, String filePath, Pageable pageable);
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
    long countByFileRoleAndValidTrue(String fileRole);
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

    @Modifying
    @Query("""
            UPDATE SongFile file
            SET file.videoCodec = COALESCE(:videoCodec, file.videoCodec),
                file.audioCodec = COALESCE(:audioCodec, file.audioCodec)
            WHERE file.id = :id
              AND (
                    (:videoCodec IS NOT NULL AND (file.videoCodec IS NULL OR TRIM(file.videoCodec) = '' OR LOWER(TRIM(file.videoCodec)) = 'unknown'))
                 OR (:audioCodec IS NOT NULL AND (file.audioCodec IS NULL OR TRIM(file.audioCodec) = '' OR LOWER(TRIM(file.audioCodec)) = 'unknown'))
              )
            """)
    int backfillProbedCodecs(@Param("id") Long id,
                             @Param("videoCodec") String videoCodec,
                             @Param("audioCodec") String audioCodec);
}
