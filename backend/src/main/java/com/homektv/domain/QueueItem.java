package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 播放队列项，对应 queue 表（详设§10）。
 * status: waiting / playing / done / skipped
 *
 * Queue item, corresponding to the queue table (Detailed Design §10).
 * status: waiting / playing / done / skipped
 */
@Entity
@Table(name = "queue")
public class QueueItem {

    /** 主键ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 歌曲ID。 / Song ID. */
    @Column(name = "song_id", nullable = false)
    private Long songId;

    /** 点歌用户ID。 / ID of the user who ordered the song. */
    @Column(name = "ordered_by")
    private Long orderedBy;

    /** 顶歌用分数插入，避免整列重排 */
    // English: Fractional insertion for song prioritization, avoiding full column reordering.
    @Column(name = "order_index", nullable = false)
    private double orderIndex;

    /** 状态：waiting（等待中）/ playing（播放中）/ done（已完成）/ skipped（已跳过）。 / Status: waiting / playing / done / skipped. */
    @Column(nullable = false)
    private String status = "waiting";

    /** 创建时间。 / Creation timestamp. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 播放时间。 / Played timestamp. */
    @Column(name = "played_at")
    private OffsetDateTime playedAt;

    /** 是否为无已点歌时的历史随机公播曲目（不占已点队列、不写入播放历史）。 */
    @Column(name = "auto_play", nullable = false)
    private boolean autoPlay = false;

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public Long getOrderedBy() { return orderedBy; }
    public void setOrderedBy(Long orderedBy) { this.orderedBy = orderedBy; }
    public double getOrderIndex() { return orderIndex; }
    public void setOrderIndex(double orderIndex) { this.orderIndex = orderIndex; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPlayedAt() { return playedAt; }
    public void setPlayedAt(OffsetDateTime playedAt) { this.playedAt = playedAt; }
    public boolean isAutoPlay() { return autoPlay; }
    public void setAutoPlay(boolean autoPlay) { this.autoPlay = autoPlay; }
}
