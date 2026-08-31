package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 歌单实体，对应 playlists 表。
 *
 * Playlist entity, mapped to the playlists table.
 */
@Entity
@Table(name = "playlists")
public class Playlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String description = "";
    private String theme;
    @Column(name = "cover_path")
    private String coverPath;
    @Column(name = "public", nullable = false)
    private boolean publicVisible = true;
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated = false;
    @Column(name = "ai_rule", columnDefinition = "text")
    private String aiRule;
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getCoverPath() { return coverPath; }
    public void setCoverPath(String coverPath) { this.coverPath = coverPath; }
    public boolean isPublicVisible() { return publicVisible; }
    public void setPublicVisible(boolean publicVisible) { this.publicVisible = publicVisible; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }
    public String getAiRule() { return aiRule; }
    public void setAiRule(String aiRule) { this.aiRule = aiRule; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
