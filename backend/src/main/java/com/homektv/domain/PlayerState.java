package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 单行播放器状态（房间唯一事实源），对应 player_state 表（详设§10）。
 * 主键固定为 1（数据库 CHECK 约束保证单行）。
 *
 * Single-row player state (the room's single source of truth), mapping to the player_state table (detailed design §10).
 * Primary key is fixed to 1 (enforced by a database CHECK constraint for single row).
 */
@Entity
@Table(name = "player_state")
public class PlayerState {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "current_queue_id")
    private Long currentQueueId;

    /** idle / playing / paused */
    // English: idle / playing / paused
    @Column(nullable = false)
    private String state = "idle";

    @Column(nullable = false)
    private int volume = 60;

    @Column(nullable = false)
    private boolean muted = false;

    /** original / accompaniment */
    // English: original / accompaniment
    @Column(name = "vocal_mode", nullable = false)
    private String vocalMode = "accompaniment";

    /** Last server-known position in milliseconds; used for reconnect/resume. */
    @Column(name = "position_ms", nullable = false)
    private long positionMs = 0;

    /** Monotonic sequence for explicit seek/replay commands. */
    @Column(name = "seek_sequence", nullable = false)
    private long seekSequence = 0;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- getters / setters ----

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }
    public Long getCurrentQueueId() { return currentQueueId; }
    public void setCurrentQueueId(Long currentQueueId) { this.currentQueueId = currentQueueId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = volume; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean muted) { this.muted = muted; }
    public String getVocalMode() { return vocalMode; }
    public void setVocalMode(String vocalMode) { this.vocalMode = vocalMode; }
    public long getPositionMs() { return positionMs; }
    public void setPositionMs(long positionMs) { this.positionMs = Math.max(0, positionMs); }
    public long getSeekSequence() { return seekSequence; }
    public void setSeekSequence(long seekSequence) { this.seekSequence = Math.max(0, seekSequence); }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
