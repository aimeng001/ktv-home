package com.homektv.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A sidecar playback derivative. It is deliberately separate from SongFile:
 * EXTERNAL_READ_ONLY sources remain immutable and the derivative has its own
 * lifecycle, fingerprint, and cache path.
 */
@Entity
@Table(name = "playback_variants", uniqueConstraints = @UniqueConstraint(
        name = "uq_playback_variant_source_profile",
        columnNames = {"source_file_id", "source_fingerprint", "profile"}))
public class PlaybackVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_file_id", nullable = false)
    private Long sourceFileId;

    @Column(name = "source_fingerprint", nullable = false, length = 128)
    private String sourceFingerprint;

    @Column(nullable = false, length = 64)
    private String profile;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "cache_path", columnDefinition = "text")
    private String cachePath;

    @Column(length = 32)
    private String format;

    @Column(name = "audio_tracks", nullable = false)
    private int audioTracks;

    @Column(name = "audio_layout", nullable = false, length = 32)
    private String audioLayout = AudioLayout.NORMAL_STEREO.name();

    @Column(name = "original_track_index")
    private Integer originalTrackIndex;

    @Column(name = "accompaniment_track_index")
    private Integer accompanimentTrackIndex;

    @Column(name = "original_channel", nullable = false, length = 16)
    private String originalChannel = AudioChannel.LEFT.name();

    @Column(name = "accompaniment_channel", nullable = false, length = 16)
    private String accompanimentChannel = AudioChannel.RIGHT.name();

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "source_size")
    private Long sourceSize;

    @Column(name = "source_mtime")
    private OffsetDateTime sourceMtime;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

    @Column(name = "last_access_at")
    private OffsetDateTime lastAccessAt;

    @Column(name = "stream_lease_until")
    private OffsetDateTime streamLeaseUntil;

    protected PlaybackVariant() {
        // JPA
    }

    public PlaybackVariant(Long sourceFileId, String sourceFingerprint, PlaybackVariantProfile profile) {
        if (sourceFileId == null || sourceFingerprint == null || sourceFingerprint.isBlank() || profile == null) {
            throw new IllegalArgumentException("source file, fingerprint and profile are required");
        }
        this.sourceFileId = sourceFileId;
        this.sourceFingerprint = sourceFingerprint;
        this.profile = profile.name();
        this.status = PlaybackVariantStatus.PREPARING.name();
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceFileId() { return sourceFileId; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public PlaybackVariantProfile getProfile() { return PlaybackVariantProfile.valueOf(profile); }
    public String getProfileValue() { return profile; }
    public PlaybackVariantStatus getStatus() { return PlaybackVariantStatus.valueOf(status); }
    public void setStatus(PlaybackVariantStatus status) { this.status = status.name(); }
    public boolean isReady() { return getStatus() == PlaybackVariantStatus.READY && cachePath != null && !cachePath.isBlank(); }
    public String getCachePath() { return cachePath; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getAudioTracks() { return audioTracks; }
    public String getAudioLayoutValue() { return audioLayout; }
    public AudioLayout getAudioLayout() { return AudioLayout.from(audioLayout); }
    public Integer getOriginalTrackIndex() { return originalTrackIndex; }
    public Integer getAccompanimentTrackIndex() { return accompanimentTrackIndex; }
    public AudioChannel getOriginalChannel() { return AudioChannel.from(originalChannel); }
    public AudioChannel getAccompanimentChannel() { return AudioChannel.from(accompanimentChannel, AudioChannel.RIGHT); }
    public long getFileSize() { return fileSize; }
    public Long getSourceSize() { return sourceSize; }
    public OffsetDateTime getSourceMtime() { return sourceMtime; }
    public String getLeaseOwner() { return leaseOwner; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getReadyAt() { return readyAt; }
    public OffsetDateTime getLastAccessAt() { return lastAccessAt; }
    public OffsetDateTime getStreamLeaseUntil() { return streamLeaseUntil; }

    public void touchAccess(OffsetDateTime now, OffsetDateTime leaseUntil) {
        this.lastAccessAt = now;
        this.streamLeaseUntil = leaseUntil;
    }

    public boolean hasActiveStreamLease(OffsetDateTime now) {
        return streamLeaseUntil != null && streamLeaseUntil.isAfter(now);
    }

    public void setSourceSnapshot(Long sourceSize, OffsetDateTime sourceMtime) {
        this.sourceSize = sourceSize;
        this.sourceMtime = sourceMtime;
    }

    public void copyAudioSemanticsFrom(SongFile source) {
        this.audioTracks = source.getAudioTracks();
        this.audioLayout = source.getAudioLayoutValue();
        this.originalTrackIndex = source.getOriginalTrackIndex();
        this.accompanimentTrackIndex = source.getAccompanimentTrackIndex();
        this.originalChannel = source.getOriginalChannelValue();
        this.accompanimentChannel = source.getAccompanimentChannelValue();
    }

    public void markPreparing(String leaseOwner, OffsetDateTime leaseUntil) {
        this.status = PlaybackVariantStatus.PREPARING.name();
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.errorCode = null;
        this.errorMessage = null;
        this.readyAt = null;
        this.streamLeaseUntil = null;
    }

    public void markReady(String cachePath, long fileSize) {
        if (cachePath == null || cachePath.isBlank() || fileSize <= 0) {
            throw new IllegalArgumentException("a non-empty cache path and positive file size are required");
        }
        this.cachePath = cachePath;
        this.fileSize = fileSize;
        this.status = PlaybackVariantStatus.READY.name();
        this.readyAt = OffsetDateTime.now();
        this.lastAccessAt = this.readyAt;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = PlaybackVariantStatus.FAILED.name();
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.readyAt = null;
        this.streamLeaseUntil = null;
    }
}
