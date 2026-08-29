package com.homektv.web.dto;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;

/**
 * 管理后台歌曲数据传输对象（DTO），用于封装歌曲及其关联文件的展示信息。
 *
 * Admin song data transfer object (DTO), encapsulating song metadata
 * along with its associated file information for admin panel display.
 */
public record AdminSongDto(
        Long id,
        String title,
        String artist,
        String album,
        String releaseDate,
        String[] aliases,
        String coverPath,
        String[] metadataLocks,
        String language,
        String artistGender,
        String[] tags,
        String mediaType,
        String lyricType,
        int durationMs,
        int playCount,
        String filePath,
        String importSource,
        Long fileId,
        int audioTracks,
        AudioLayoutDto audioLayout
) {
    /**
     * 根据歌曲实体和文件实体构建管理后台歌曲 DTO，自动判断导入来源。
     *
     * Builds an admin song DTO from a song entity and file entity,
     * automatically determining the import source.
     *
     * @param song 歌曲实体 / the song entity
     * @param file 歌曲文件实体，可为 null / the song file entity, nullable
     * @return 构建好的管理后台歌曲 DTO / the constructed admin song DTO
     */
    public static AdminSongDto from(Song song, SongFile file) {
        String source = file == null ? "UNKNOWN"
                : "EXTERNAL_READ_ONLY".equals(file.getFileRole()) ? "EXTERNAL_READ_ONLY"
                : file.getSourcePath() == null ? "UNKNOWN"
                : file.isTranscodeRequired() ? "TRANSCODED" : "COPIED";
        return new AdminSongDto(song.getId(), song.getTitle(), song.getArtist(), song.getAlbum(), song.getReleaseDate(),
                song.getAliases(), song.getCoverPath(), song.getMetadataLocks(), song.getLanguage(), song.getArtistGender(), song.getTags(),
                song.getMediaType(), song.getLyricType(), song.getDurationMs(), song.getPlayCount(),
                file == null ? null : file.getFilePath(), source,
                file == null ? null : file.getId(),
                file == null ? 0 : file.getAudioTracks(),
                AudioLayoutDto.from(file));
    }
}
