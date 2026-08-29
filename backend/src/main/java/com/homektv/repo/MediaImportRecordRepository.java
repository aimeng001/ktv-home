package com.homektv.repo;

import com.homektv.domain.MediaImportRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 媒体导入记录 Repository，对应 media_import_records 表，提供媒体文件导入记录的持久化操作。
 *
 * Media import record repository, maps to the media_import_records table, providing
 * persistence operations for media file import records.
 */
public interface MediaImportRecordRepository extends JpaRepository<MediaImportRecord, Long> {
    Optional<MediaImportRecord> findBySourcePath(String sourcePath);
    boolean existsBySourceMd5(String sourceMd5);
    boolean existsBySourceMd5AndSourcePathNot(String sourceMd5, String sourcePath);
    boolean existsByOutputMd5(String outputMd5);
    boolean existsByOutputMd5AndSourcePathNot(String outputMd5, String sourcePath);
    Page<MediaImportRecord> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    Page<MediaImportRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<MediaImportRecord> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
    @Query("""
            SELECT record FROM MediaImportRecord record
            WHERE (:keyword = ''
                OR LOWER(record.sourceFilename) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(record.parsedTitle, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(record.parsedArtist, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:action IS NULL OR record.action = :action)
              AND (:duplicateFlag IS NULL OR record.duplicateFlag = :duplicateFlag)
              AND (:transcodeRequired IS NULL OR record.transcodeRequired = :transcodeRequired)
              AND (:sourceDeleted IS NULL OR record.sourceDeleted = :sourceDeleted)
            ORDER BY record.createdAt DESC
            """)
    Page<MediaImportRecord> searchSourceLibrary(@Param("keyword") String keyword,
                                                @Param("action") String action,
                                                @Param("duplicateFlag") Boolean duplicateFlag,
                                                @Param("transcodeRequired") Boolean transcodeRequired,
                                                @Param("sourceDeleted") Boolean sourceDeleted,
                                                Pageable pageable);

    @Query("""
            SELECT record FROM MediaImportRecord record
            WHERE record.id > :afterId
              AND (:keyword = ''
                OR LOWER(record.sourceFilename) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(record.parsedTitle, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(record.parsedArtist, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:action IS NULL OR record.action = :action)
              AND (:duplicateFlag IS NULL OR record.duplicateFlag = :duplicateFlag)
              AND (:transcodeRequired IS NULL OR record.transcodeRequired = :transcodeRequired)
              AND (:sourceDeleted IS NULL OR record.sourceDeleted = :sourceDeleted)
            ORDER BY record.id ASC
            """)
    Slice<MediaImportRecord> searchSourceLibraryAfterId(@Param("afterId") Long afterId,
                                                        @Param("keyword") String keyword,
                                                        @Param("action") String action,
                                                        @Param("duplicateFlag") Boolean duplicateFlag,
                                                        @Param("transcodeRequired") Boolean transcodeRequired,
                                                        @Param("sourceDeleted") Boolean sourceDeleted,
                                                        Pageable pageable);
    List<MediaImportRecord> findAllByOrderByCreatedAtDesc();
    List<MediaImportRecord> findByIdIn(Collection<Long> ids);
    List<MediaImportRecord> findBySongId(Long songId);
    List<MediaImportRecord> findBySongFileId(Long songFileId);
}
