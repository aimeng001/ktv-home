package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 媒体导入记录实体，用于跟踪媒体文件从导入、解析到转码的完整处理流程。
 *
 * Media import record entity that tracks the complete processing flow of media files
 * from import, parsing to transcoding.
 */
@Entity
@Table(name = "media_import_records")
public class MediaImportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_path", nullable = false, unique = true)
    private String sourcePath;

    @Column(name = "source_filename", nullable = false)
    private String sourceFilename;

    @Column(name = "source_md5", nullable = false)
    private String sourceMd5;

    /** Fast source snapshot used to avoid hashing an unchanged Managed source. */
    @Column(name = "source_size")
    private Long sourceSize;

    @Column(name = "source_mtime")
    private OffsetDateTime sourceMtime;

    @Column(name = "source_file_identity")
    private String sourceFileIdentity;

    @Column(name = "parsed_title")
    private String parsedTitle;

    @Column(name = "parsed_artist")
    private String parsedArtist;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "source_format")
    private String sourceFormat;

    @Column(name = "output_path")
    private String outputPath;

    @Column(name = "output_md5")
    private String outputMd5;

    /** Fast output snapshot used to avoid rehashing a verified import during cleanup. */
    @Column(name = "output_size")
    private Long outputSize;

    @Column(name = "output_mtime")
    private OffsetDateTime outputMtime;

    @Column(name = "output_format")
    private String outputFormat;

    @Column(name = "video_codec")
    private String videoCodec;

    @Column(name = "audio_codec")
    private String audioCodec;

    @Column(nullable = false)
    private String action;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "song_id")
    private Long songId;

    @Column(name = "song_file_id")
    private Long songFileId;

    @Column(name = "transcode_required", nullable = false)
    private boolean transcodeRequired;

    @Column(name = "duplicate_flag", nullable = false)
    private boolean duplicateFlag;

    @Column(name = "imported_flag", nullable = false)
    private boolean importedFlag;

    @Column(name = "source_deleted", nullable = false)
    private boolean sourceDeleted;

    @Column(name = "delete_source_requested", nullable = false)
    private boolean deleteSourceRequested;

    @Column(name = "cleanup_status", nullable = false)
    private String cleanupStatus = "NOT_REQUESTED";

    @Column(name = "cleanup_error", columnDefinition = "text")
    private String cleanupError;

    @Column(name = "cleanup_attempted_at")
    private OffsetDateTime cleanupAttemptedAt;

    @Column(name = "companion_files", columnDefinition = "jsonb", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String companionFiles = "[]";

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- getters / setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getSourceFilename() { return sourceFilename; }
    public void setSourceFilename(String sourceFilename) { this.sourceFilename = sourceFilename; }
    public String getSourceMd5() { return sourceMd5; }
    public void setSourceMd5(String sourceMd5) { this.sourceMd5 = sourceMd5; }
    public Long getSourceSize() { return sourceSize; }
    public void setSourceSize(Long sourceSize) { this.sourceSize = sourceSize; }
    public OffsetDateTime getSourceMtime() { return sourceMtime; }
    public void setSourceMtime(OffsetDateTime sourceMtime) { this.sourceMtime = sourceMtime; }
    public String getSourceFileIdentity() { return sourceFileIdentity; }
    public void setSourceFileIdentity(String sourceFileIdentity) { this.sourceFileIdentity = sourceFileIdentity; }
    public String getParsedTitle() { return parsedTitle; }
    public void setParsedTitle(String parsedTitle) { this.parsedTitle = parsedTitle; }
    public String getParsedArtist() { return parsedArtist; }
    public void setParsedArtist(String parsedArtist) { this.parsedArtist = parsedArtist; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getSourceFormat() { return sourceFormat; }
    public void setSourceFormat(String sourceFormat) { this.sourceFormat = sourceFormat; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public String getOutputMd5() { return outputMd5; }
    public void setOutputMd5(String outputMd5) { this.outputMd5 = outputMd5; }
    public Long getOutputSize() { return outputSize; }
    public void setOutputSize(Long outputSize) { this.outputSize = outputSize; }
    public OffsetDateTime getOutputMtime() { return outputMtime; }
    public void setOutputMtime(OffsetDateTime outputMtime) { this.outputMtime = outputMtime; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public Long getSongFileId() { return songFileId; }
    public void setSongFileId(Long songFileId) { this.songFileId = songFileId; }
    public boolean isTranscodeRequired() { return transcodeRequired; }
    public void setTranscodeRequired(boolean transcodeRequired) { this.transcodeRequired = transcodeRequired; }
    public boolean isDuplicateFlag() { return duplicateFlag; }
    public void setDuplicateFlag(boolean duplicateFlag) { this.duplicateFlag = duplicateFlag; }
    public boolean isImportedFlag() { return importedFlag; }
    public void setImportedFlag(boolean importedFlag) { this.importedFlag = importedFlag; }
    public boolean isSourceDeleted() { return sourceDeleted; }
    public void setSourceDeleted(boolean sourceDeleted) { this.sourceDeleted = sourceDeleted; }
    public boolean isDeleteSourceRequested() { return deleteSourceRequested; }
    public void setDeleteSourceRequested(boolean deleteSourceRequested) { this.deleteSourceRequested = deleteSourceRequested; }
    public String getCleanupStatus() { return cleanupStatus; }
    public void setCleanupStatus(String cleanupStatus) { this.cleanupStatus = cleanupStatus; }
    public String getCleanupError() { return cleanupError; }
    public void setCleanupError(String cleanupError) { this.cleanupError = cleanupError; }
    public OffsetDateTime getCleanupAttemptedAt() { return cleanupAttemptedAt; }
    public void setCleanupAttemptedAt(OffsetDateTime cleanupAttemptedAt) { this.cleanupAttemptedAt = cleanupAttemptedAt; }
    public String getCompanionFiles() { return companionFiles; }
    public void setCompanionFiles(String companionFiles) { this.companionFiles = companionFiles; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
