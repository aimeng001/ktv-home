package com.homektv.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 歌曲文件源（一首歌可对应多个文件），对应 song_files 表（详设§10）。
 *
 * Song file source (one song can have multiple files), mapped to the {@code song_files} table (detailed design §10).
 */
@Entity
@Table(name = "song_files")
public class SongFile {

    /** 主键 ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的歌曲 ID。 / Associated song ID. */
    @Column(name = "song_id", nullable = false)
    private Long songId;

    /** 文件路径，唯一。 / File path, unique. */
    @Column(name = "file_path", nullable = false, unique = true)
    private String filePath;

    /** Path relative to the active library root; kept separate from the runtime path. */
    @Column(name = "relative_path")
    private String relativePath;

    /** 文件格式（如 MKV、MP4 等）。 / File format (e.g. MKV, MP4, etc.). */
    @Column(nullable = false)
    private String format;

    /** 音轨数量，默认 1。 / Number of audio tracks, defaults to 1. */
    @Column(name = "audio_tracks", nullable = false)
    private int audioTracks = 1;

    /** Platform-neutral audio layout; old rows default to normal stereo. */
    @Column(name = "audio_layout", nullable = false)
    private String audioLayout = AudioLayout.NORMAL_STEREO.name();

    /** Semantic track indices used by DUAL_TRACK; nullable for channel layouts. */
    @Column(name = "original_track_index")
    private Integer originalTrackIndex;

    @Column(name = "accompaniment_track_index")
    private Integer accompanimentTrackIndex;

    /** Semantic channel mapping used by DUAL_CHANNEL. */
    @Column(name = "original_channel", nullable = false)
    private String originalChannel = AudioChannel.LEFT.name();

    @Column(name = "accompaniment_channel", nullable = false)
    private String accompanimentChannel = AudioChannel.RIGHT.name();

    /** 伴奏轨 index（0-based 音频序号，入库探测，免运行时猜轨），可空 */
    // English: Accompaniment track index (0-based audio sequence, detected at import to avoid runtime guessing), nullable.
    @Column(name = "vocal_track_index")
    private Integer vocalTrackIndex;

    /** 伴奏轨判定置信度：HIGH/MEDIUM/LOW/NONE，可空；LOW 供后台筛选人工复核 */
    // English: Accompaniment track detection confidence: HIGH/MEDIUM/LOW/NONE, nullable; LOW is used for backend filtering and manual review.
    @Column(name = "vocal_confidence")
    private String vocalConfidence;

    /** 视频分辨率（如 1080p）。 / Video resolution (e.g. 1080p). */
    private String resolution;

    /** 文件大小（字节），默认 0。 / File size in bytes, defaults to 0. */
    @Column(name = "file_size", nullable = false)
    private long fileSize = 0;

    /** 文件修改时间。 / File modification time. */
    @Column(name = "file_mtime", nullable = false)
    private OffsetDateTime fileMtime;

    /** 优先级，默认 0（数值越大越优先）。 / Priority, defaults to 0 (higher value = higher priority). */
    @Column(nullable = false)
    private int priority = 0;
    /** 是否有效，默认 true。 / Whether the file is valid, defaults to true. */
    @Column(nullable = false)
    private boolean valid = true;
    /** 文件角色（如 LIBRARY），默认 "LIBRARY"。 / File role (e.g. LIBRARY), defaults to "LIBRARY". */
    @Column(name = "file_role", nullable = false)
    private String fileRole = "LIBRARY";
    /** 源文件路径。 / Source file path. */
    @Column(name = "source_path")
    private String sourcePath;
    /** 源文件 MD5 哈希。 / Source file MD5 hash. */
    @Column(name = "source_md5")
    private String sourceMd5;
    /** 转码输出文件 MD5 哈希。 / Transcode output file MD5 hash. */
    @Column(name = "output_md5")
    private String outputMd5;
    /** 是否需要转码，默认 false。 / Whether transcoding is required, defaults to false. */
    @Column(name = "transcode_required", nullable = false)
    private boolean transcodeRequired;
    /** 导入时间。 / Import time. */
    @Column(name = "imported_at")
    private OffsetDateTime importedAt;
    /** 源文件是否已删除，默认 false。 / Whether the source file has been deleted, defaults to false. */
    @Column(name = "source_deleted", nullable = false)
    private boolean sourceDeleted;

    /** Filesystem identity captured by Fast Index when the provider exposes one. */
    @Column(name = "file_identity")
    private String fileIdentity;

    /** True while the row contains filename metadata but has not passed FFprobe. */
    @Column(name = "probe_pending", nullable = false)
    private boolean probePending;

    // ---- getters / setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getAudioTracks() { return audioTracks; }
    public void setAudioTracks(int audioTracks) { this.audioTracks = audioTracks; }
    public AudioLayout getAudioLayout() { return AudioLayout.from(audioLayout); }
    public String getAudioLayoutValue() { return audioLayout; }
    public void setAudioLayout(AudioLayout audioLayout) {
        this.audioLayout = (audioLayout == null ? AudioLayout.NORMAL_STEREO : audioLayout).name();
    }
    public void setAudioLayoutValue(String audioLayout) {
        this.audioLayout = audioLayout == null ? AudioLayout.NORMAL_STEREO.name() : audioLayout;
    }
    public Integer getOriginalTrackIndex() {
        if (originalTrackIndex != null) return originalTrackIndex;
        Integer accompaniment = getAccompanimentTrackIndex();
        if (getAudioLayout() == AudioLayout.DUAL_TRACK && accompaniment != null && audioTracks > 1) {
            return accompaniment == 0 ? 1 : 0;
        }
        return null;
    }
    public void setOriginalTrackIndex(Integer originalTrackIndex) { this.originalTrackIndex = originalTrackIndex; }
    public Integer getAccompanimentTrackIndex() {
        return accompanimentTrackIndex != null ? accompanimentTrackIndex : vocalTrackIndex;
    }
    public void setAccompanimentTrackIndex(Integer accompanimentTrackIndex) {
        this.accompanimentTrackIndex = accompanimentTrackIndex;
        this.vocalTrackIndex = accompanimentTrackIndex;
    }
    public AudioChannel getOriginalChannel() { return AudioChannel.from(originalChannel); }
    public String getOriginalChannelValue() { return originalChannel; }
    public void setOriginalChannel(AudioChannel originalChannel) {
        this.originalChannel = (originalChannel == null ? AudioChannel.LEFT : originalChannel).name();
    }
    public void setOriginalChannelValue(String originalChannel) {
        this.originalChannel = originalChannel == null ? AudioChannel.LEFT.name() : originalChannel;
    }
    public AudioChannel getAccompanimentChannel() { return AudioChannel.from(accompanimentChannel, AudioChannel.RIGHT); }
    public String getAccompanimentChannelValue() { return accompanimentChannel; }
    public void setAccompanimentChannel(AudioChannel accompanimentChannel) {
        this.accompanimentChannel = (accompanimentChannel == null ? AudioChannel.RIGHT : accompanimentChannel).name();
    }
    public void setAccompanimentChannelValue(String accompanimentChannel) {
        this.accompanimentChannel = accompanimentChannel == null ? AudioChannel.RIGHT.name() : accompanimentChannel;
    }
    public Integer getVocalTrackIndex() { return vocalTrackIndex; }
    public void setVocalTrackIndex(Integer vocalTrackIndex) {
        this.vocalTrackIndex = vocalTrackIndex;
        this.accompanimentTrackIndex = vocalTrackIndex;
    }
    public String getVocalConfidence() { return vocalConfidence; }
    public void setVocalConfidence(String vocalConfidence) { this.vocalConfidence = vocalConfidence; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public OffsetDateTime getFileMtime() { return fileMtime; }
    public void setFileMtime(OffsetDateTime fileMtime) { this.fileMtime = fileMtime; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public String getFileRole() { return fileRole; }
    public void setFileRole(String fileRole) { this.fileRole = fileRole; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getSourceMd5() { return sourceMd5; }
    public void setSourceMd5(String sourceMd5) { this.sourceMd5 = sourceMd5; }
    public String getOutputMd5() { return outputMd5; }
    public void setOutputMd5(String outputMd5) { this.outputMd5 = outputMd5; }
    public boolean isTranscodeRequired() { return transcodeRequired; }
    public void setTranscodeRequired(boolean transcodeRequired) { this.transcodeRequired = transcodeRequired; }
    public OffsetDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
    public boolean isSourceDeleted() { return sourceDeleted; }
    public void setSourceDeleted(boolean sourceDeleted) { this.sourceDeleted = sourceDeleted; }
    public String getFileIdentity() { return fileIdentity; }
    public void setFileIdentity(String fileIdentity) { this.fileIdentity = fileIdentity; }
    public boolean isProbePending() { return probePending; }
    public void setProbePending(boolean probePending) { this.probePending = probePending; }

    /** Swap only semantic vocal/accompaniment assignments; media bytes are untouched. */
    public void swapOriginalAndAccompaniment() {
        switch (getAudioLayout()) {
            case DUAL_CHANNEL -> {
                AudioChannel original = getOriginalChannel();
                setOriginalChannel(getAccompanimentChannel());
                setAccompanimentChannel(original);
            }
            case DUAL_TRACK -> {
                Integer original = getOriginalTrackIndex();
                Integer accompaniment = getAccompanimentTrackIndex();
                if (original == null || accompaniment == null) return;
                setOriginalTrackIndex(accompaniment);
                setAccompanimentTrackIndex(original);
            }
            case NORMAL_STEREO -> { /* no vocal semantics to swap */ }
        }
    }
}
