package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 心愿单（缺歌反馈），对应 wishes 表（详设§10）。
 *
 * Wish list (missing song feedback), corresponding to the wishes table (detailed design §10).
 */
@Entity
@Table(name = "wishes")
public class Wish {

    /** 主键ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 歌曲关键词。 / Song keyword. */
    @Column(nullable = false)
    private String keyword;

    /** 创建人ID。 / Creator ID. */
    @Column(name = "created_by")
    private Long createdBy;

    /** 创建时间。 / Creation time. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ---- getters / setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
