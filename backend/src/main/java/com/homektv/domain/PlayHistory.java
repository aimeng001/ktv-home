package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 已播历史，对应 play_history 表（详设§10）。
 *
 * Play history record, corresponding to the play_history table (detail design §10).
 */
@Entity
@Table(name = "play_history")
public class PlayHistory {

    /** 主键ID。 */
    // English: Primary key ID.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 歌曲ID。 */
    // English: Song ID.
    @Column(name = "song_id", nullable = false)
    private Long songId;

    /** 产生该历史记录的队列项ID，用于完成事件幂等。 */
    // English: Queue item that produced this history row, used for completion idempotency.
    @Column(name = "queue_id")
    private Long queueId;

    /** 点歌人ID。 */
    // English: ID of the user who requested the song.
    @Column(name = "played_by")
    private Long playedBy;

    /** 播放时间。 */
    // English: Playback timestamp.
    @Generated(event = EventType.INSERT)
    @Column(name = "played_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime playedAt;

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public Long getQueueId() { return queueId; }
    public void setQueueId(Long queueId) { this.queueId = queueId; }
    public Long getPlayedBy() { return playedBy; }
    public void setPlayedBy(Long playedBy) { this.playedBy = playedBy; }
    public OffsetDateTime getPlayedAt() { return playedAt; }
}
