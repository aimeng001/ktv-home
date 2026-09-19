package com.homektv.web.dto;

import com.homektv.domain.Song;
import com.homektv.library.ArtistAvatarUrl;

/**
 * 歌曲列表/搜索结果项（详设§11.1）。
 *
 * Song list / search result item (see detailed design &sect;11.1).
 */
public record SongDto(
        Long id,
        String catalogNumber,
        String title,
        String artist,
        String artistGender,
        String mediaType,
        boolean hasVocalTrack,
        int durationMs,
        String lyricType,
        String coverUrl,
        int playCount,
        String artistAvatarUrl,
        boolean playable,
        String unavailableReason
) {
    /** Source-compatible constructor for callers using the original protocol fields. */
    public SongDto(Long id, String title, String artist, String artistGender, String mediaType,
                   boolean hasVocalTrack, int durationMs, String lyricType, String coverUrl,
                   int playCount) {
        this(id, "", title, artist, artistGender, mediaType, hasVocalTrack, durationMs, lyricType,
                coverUrl, playCount, ArtistAvatarUrl.forCredit(artist), true, null);
    }

    /** Source-compatible constructor for callers that also supplied an avatar URL. */
    public SongDto(Long id, String title, String artist, String artistGender, String mediaType,
                   boolean hasVocalTrack, int durationMs, String lyricType, String coverUrl,
                   int playCount, String artistAvatarUrl) {
        this(id, "", title, artist, artistGender, mediaType, hasVocalTrack, durationMs, lyricType,
                coverUrl, playCount, artistAvatarUrl, true, null);
    }
    /**
     * 将 {@link Song} 领域对象转换为 SongDto，封面路径组装为 API 访问地址，无封面时返回 {@code null}。
     *
     * Converts a {@link Song} domain object to a SongDto. The cover path is assembled as an API URL,
     * or {@code null} when no cover is set.
     *
     * @param s 歌曲领域对象 / the song domain object
     * @return 对应的 SongDto / the corresponding SongDto
     */
    public static SongDto from(Song s) {
        return from(s, true, null);
    }

    /** Converts a song while carrying the server-side batch readiness decision. */
    public static SongDto from(Song s, boolean playable, String unavailableReason) {
        return new SongDto(
                s.getId(),
                s.getCatalogNumber(),
                s.getTitle(),
                s.getArtist(),
                s.getArtistGender(),
                s.getMediaType(),
                s.isHasVocalTrack(),
                s.getDurationMs(),
                s.getLyricType(),
                s.getCoverPath() != null ? "/api/cover/" + s.getId() : null,
                s.getPlayCount(),
                ArtistAvatarUrl.forCredit(s.getArtist()),
                playable,
                playable ? null : unavailableReason
        );
    }
}
