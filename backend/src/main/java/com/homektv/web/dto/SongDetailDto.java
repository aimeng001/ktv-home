package com.homektv.web.dto;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;

import java.util.List;

/**
 * 歌曲详情（详设§11.1）：含歌词 URL、可用文件源/音轨信息。
 *
 * Song detail (design spec §11.1): includes lyric URL, available file sources/audio track information.
 */
public record SongDetailDto(
        Long id,
        String title,
        String artist,
        String language,
        String[] tags,
        String mediaType,
        boolean hasVocalTrack,
        int durationMs,
        String lyricType,
        String coverUrl,
        String lyricUrl,
        int playCount,
        List<FileSourceDto> files
) {
    /**
     * 文件源信息：包含格式、音轨数、人声轨道索引等。
     *
     * File source information: includes format, audio track count, vocal track index, etc.
     */
    public record FileSourceDto(Long id, String format, int audioTracks, Integer vocalTrackIndex,
                                String vocalConfidence, String resolution, int priority,
                                AudioLayoutDto audioLayout) {
        /** Source-compatible constructor for callers that only know the legacy fields. */
        public FileSourceDto(Long id, String format, int audioTracks, Integer vocalTrackIndex,
                             String vocalConfidence, String resolution, int priority) {
            this(id, format, audioTracks, vocalTrackIndex, vocalConfidence, resolution, priority,
                    AudioLayoutDto.normalStereo());
        }

        /**
         * 从 SongFile 实体创建 FileSourceDto。
         *
         * Create FileSourceDto from a SongFile entity.
         * @param f SongFile 实体 / SongFile entity
         * @return FileSourceDto 实例 / FileSourceDto instance
         */
        static FileSourceDto from(SongFile f) {
            return new FileSourceDto(f.getId(), f.getFormat(), f.getAudioTracks(),
                    f.getVocalTrackIndex(), f.getVocalConfidence(), f.getResolution(), f.getPriority(),
                    AudioLayoutDto.from(f));
        }
    }

    /**
     * 从 Song 实体和关联的文件列表创建 SongDetailDto。
     *
     * Create SongDetailDto from a Song entity and associated file list.
     * @param s     Song 实体 / Song entity
     * @param files 关联的歌曲文件列表 / associated song file list
     * @return SongDetailDto 实例 / SongDetailDto instance
     */
    public static SongDetailDto from(Song s, List<SongFile> files) {
        return new SongDetailDto(
                s.getId(), s.getTitle(), s.getArtist(), s.getLanguage(), s.getTags(),
                s.getMediaType(), s.isHasVocalTrack(), s.getDurationMs(), s.getLyricType(),
                s.getCoverPath() != null ? "/api/cover/" + s.getId() : null,
                !"none".equals(s.getLyricType()) ? "/api/lyric/" + s.getId() : null,
                s.getPlayCount(),
                files.stream().map(FileSourceDto::from).toList()
        );
    }
}
