package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 歌曲实体，对应 songs 表（详设§10）。
 *
 * Song entity, corresponding to the songs table (see §10 of the detailed design).
 */
@Entity
@Table(name = "songs")
public class Song {

    public static final String LYRIC_SOURCE_UNKNOWN = "UNKNOWN";
    public static final String LYRIC_SOURCE_NONE = "NONE";
    public static final String LYRIC_SOURCE_SIDECAR = "SIDECAR";
    public static final String LYRIC_SOURCE_EMBEDDED = "EMBEDDED";
    public static final String LYRIC_SOURCE_MANUAL = "MANUAL";

    /** 主键ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optional fixed-width catalog number parsed from an 8-digit filename prefix. */
    @Column(name = "catalog_number", length = 8)
    private String catalogNumber = "";

    /** 歌曲标题。 / Song title. */
    @Column(nullable = false)
    private String title;

    /** 歌手名称，默认为"未知歌手"。 / Artist name, defaults to "未知歌手". */
    @Column(nullable = false)
    private String artist = "未知歌手";

    /** 歌曲标题拼音。 / Pinyin of the song title. */
    @Column(name = "title_py", nullable = false)
    private String titlePy = "";

    /** 歌曲标题首字母。 / Initials of the song title. */
    @Column(name = "title_init", nullable = false)
    private String titleInit = "";

    /** 歌手名称拼音。 / Pinyin of the artist name. */
    @Column(name = "artist_py", nullable = false)
    private String artistPy = "";

    /** 歌手名称首字母。 / Initials of the artist name. */
    @Column(name = "artist_init", nullable = false)
    private String artistInit = "";

    /** Canonical language; unknown unless supported by trusted evidence. */
    @Column(nullable = false)
    private String language = "未知";

    @Column(name = "vocal_form", nullable = false)
    private String vocalForm = "未知";

    @Column(name = "artist_gender", nullable = false)
    private String artistGender = "未知";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "metadata_locks", columnDefinition = "text[]", nullable = false)
    private String[] metadataLocks = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_provenance", columnDefinition = "jsonb", nullable = false)
    private String metadataProvenance = "{}";

    @Column(name = "needs_ai_optimization", nullable = false)
    private boolean needsAiOptimization;

    /** 标签列表。 / Tag list. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]", nullable = false)
    private String[] tags = new String[0];

    /** 媒体类型：KTV_VIDEO / MV / AUDIO。 / Media type: KTV_VIDEO / MV / AUDIO. */
    @Column(name = "media_type", nullable = false)
    private String mediaType;

    /** 是否有伴唱音轨，默认false。 / Has vocal track, defaults to false. */
    @Column(name = "has_vocal_track", nullable = false)
    private boolean hasVocalTrack = false;

    /** 歌曲时长（毫秒），默认0。 / Song duration in milliseconds, defaults to 0. */
    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    /** 封面图片路径。 / Cover image path. */
    @Column(name = "cover_path")
    private String coverPath;

    @Column(name = "album")
    private String album;

    @Column(name = "release_date")
    private String releaseDate;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", columnDefinition = "text[]", nullable = false)
    private String[] aliases = new String[0];

    /** 歌词文件路径。 / Lyric file path. */
    @Column(name = "lyric_path")
    private String lyricPath;

    /** 歌词类型：word / line / sub / none。 / Lyric type: word / line / sub / none. */
    @Column(name = "lyric_type", nullable = false)
    private String lyricType = "none";

    /** Provenance of the cached lyric; unknown legacy rows are never cleared automatically. */
    @Column(name = "lyric_source", nullable = false)
    private String lyricSource = LYRIC_SOURCE_UNKNOWN;

    /** 播放次数，默认0。 / Play count, defaults to 0. */
    @Column(name = "play_count", nullable = false)
    private int playCount = 0;

    /** AI分析的语言。 / AI-analyzed language. */
    @Column(name = "ai_language")
    private String aiLanguage;

    /** AI分析的时代。 / AI-analyzed era. */
    @Column(name = "ai_era")
    private String aiEra;

    /** AI分析的流派列表。 / AI-analyzed genre list. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "ai_genres", columnDefinition = "text[]", nullable = false)
    private String[] aiGenres = new String[0];

    /** AI分析的主题列表。 / AI-analyzed theme list. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "ai_themes", columnDefinition = "text[]", nullable = false)
    private String[] aiThemes = new String[0];

    /** AI分析的年龄段。 / AI-analyzed age range. */
    @Column(name = "ai_age_range")
    private String aiAgeRange;

    /** AI分析的演唱形式。 / AI-analyzed vocal form. */
    @Column(name = "ai_vocal_form")
    private String aiVocalForm;

    /** AI分析时间。 / AI analysis timestamp. */
    @Column(name = "ai_analyzed_at")
    private OffsetDateTime aiAnalyzedAt;

    /** 状态：ok / file_missing。 / Status: ok / file_missing. */
    @Column(nullable = false)
    private String status = "ok";

    /** 文件指纹（唯一标识）。 / File fingerprint (unique identifier). */
    @Column(nullable = false, unique = true)
    private String fingerprint;

    /** 创建时间，由数据库自动生成。 / Creation timestamp, auto-generated by the database. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 更新时间，由数据库自动生成。 / Update timestamp, auto-generated by the database. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- getters / setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCatalogNumber() { return catalogNumber == null ? "" : catalogNumber; }
    public void setCatalogNumber(String catalogNumber) {
        String value = catalogNumber == null ? "" : catalogNumber.trim();
        this.catalogNumber = value.matches("\\d{8}") ? value : "";
    }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public String getTitlePy() { return titlePy; }
    public void setTitlePy(String titlePy) { this.titlePy = titlePy; }
    public String getTitleInit() { return titleInit; }
    public void setTitleInit(String titleInit) { this.titleInit = titleInit; }
    public String getArtistPy() { return artistPy; }
    public void setArtistPy(String artistPy) { this.artistPy = artistPy; }
    public String getArtistInit() { return artistInit; }
    public void setArtistInit(String artistInit) { this.artistInit = artistInit; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getVocalForm() { return vocalForm; }
    public void setVocalForm(String vocalForm) { this.vocalForm = vocalForm; }
    public String getArtistGender() { return artistGender; }
    public void setArtistGender(String artistGender) { this.artistGender = artistGender; }
    public String[] getMetadataLocks() { return metadataLocks; }
    public void setMetadataLocks(String[] metadataLocks) { this.metadataLocks = metadataLocks == null ? new String[0] : metadataLocks; }
    public String getMetadataProvenance() { return metadataProvenance; }
    public void setMetadataProvenance(String metadataProvenance) { this.metadataProvenance = metadataProvenance; }
    public boolean isNeedsAiOptimization() { return needsAiOptimization; }
    public void setNeedsAiOptimization(boolean needsAiOptimization) { this.needsAiOptimization = needsAiOptimization; }
    public boolean isMetadataLocked(String field) {
        return java.util.Arrays.stream(metadataLocks == null ? new String[0] : metadataLocks).anyMatch(field::equals);
    }
    public void lockMetadata(String field) {
        java.util.LinkedHashSet<String> locks = new java.util.LinkedHashSet<>(java.util.Arrays.asList(metadataLocks == null ? new String[0] : metadataLocks));
        locks.add(field);
        metadataLocks = locks.toArray(String[]::new);
    }
    public String[] getTags() { return tags; }
    public void setTags(String[] tags) { this.tags = tags; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public boolean isHasVocalTrack() { return hasVocalTrack; }
    public void setHasVocalTrack(boolean hasVocalTrack) { this.hasVocalTrack = hasVocalTrack; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public String getCoverPath() { return coverPath; }
    public void setCoverPath(String coverPath) { this.coverPath = coverPath; }
    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }
    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public String[] getAliases() { return aliases; }
    public void setAliases(String[] aliases) { this.aliases = aliases == null ? new String[0] : aliases; }
    public String getLyricPath() { return lyricPath; }
    public void setLyricPath(String lyricPath) { this.lyricPath = lyricPath; }
    public String getLyricType() { return lyricType; }
    public void setLyricType(String lyricType) { this.lyricType = lyricType; }
    public String getLyricSource() { return lyricSource; }
    public void setLyricSource(String lyricSource) {
        this.lyricSource = lyricSource == null || lyricSource.isBlank()
                ? LYRIC_SOURCE_UNKNOWN : lyricSource;
    }
    public int getPlayCount() { return playCount; }
    public void setPlayCount(int playCount) { this.playCount = playCount; }
    public String getAiLanguage() { return aiLanguage; }
    public void setAiLanguage(String aiLanguage) { this.aiLanguage = aiLanguage; }
    public String getAiEra() { return aiEra; }
    public void setAiEra(String aiEra) { this.aiEra = aiEra; }
    public String[] getAiGenres() { return aiGenres; }
    public void setAiGenres(String[] aiGenres) { this.aiGenres = aiGenres; }
    public String[] getAiThemes() { return aiThemes; }
    public void setAiThemes(String[] aiThemes) { this.aiThemes = aiThemes; }
    public String getAiAgeRange() { return aiAgeRange; }
    public void setAiAgeRange(String aiAgeRange) { this.aiAgeRange = aiAgeRange; }
    public String getAiVocalForm() { return aiVocalForm; }
    public void setAiVocalForm(String aiVocalForm) { this.aiVocalForm = aiVocalForm; }
    public OffsetDateTime getAiAnalyzedAt() { return aiAnalyzedAt; }
    public void setAiAnalyzedAt(OffsetDateTime aiAnalyzedAt) { this.aiAnalyzedAt = aiAnalyzedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
