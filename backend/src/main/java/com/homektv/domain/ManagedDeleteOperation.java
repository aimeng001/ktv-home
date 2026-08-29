package com.homektv.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/** Durable journal for a recoverable Managed-library file deletion. */
@Entity
@Table(name = "managed_delete_operations")
public class ManagedDeleteOperation {

    public static final String PREPARED = "PREPARED";
    public static final String STAGED = "STAGED";
    public static final String COMMITTED = "COMMITTED";
    public static final String PURGED = "PURGED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";

    @Id
    @Column(name = "operation_id", length = 36, nullable = false)
    private String operationId;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(nullable = false, length = 32)
    private String status;

    /** JSON array of {sourcePath, trashPath}; paths are validated before use. */
    @Column(nullable = false, columnDefinition = "text")
    private String manifest;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getManifest() { return manifest; }
    public void setManifest(String manifest) { this.manifest = manifest; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
