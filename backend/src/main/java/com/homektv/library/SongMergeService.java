package com.homektv.library;

import com.homektv.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SongMergeService {
    private final JdbcTemplate jdbc;

    public SongMergeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> merge(Long keepSongId, Long sourceSongId) {
        if (keepSongId == null || sourceSongId == null || keepSongId.equals(sourceSongId))
            throw new ApiException("INVALID_SONG_MERGE", "保留歌曲和待合并歌曲必须是两首不同歌曲");

        List<Long> locked = jdbc.queryForList(
                "SELECT id FROM songs WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                Long.class, keepSongId, sourceSongId);
        if (!locked.contains(keepSongId) || !locked.contains(sourceSongId))
            throw new ApiException("SONG_NOT_FOUND", "保留歌曲或待合并歌曲不存在");

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("playlistSongs", mergePlaylistSongs(keepSongId, sourceSongId));
        counts.put("favorites", mergeFavorites(keepSongId, sourceSongId));
        counts.put("queueItems", jdbc.update("UPDATE queue SET song_id = ? WHERE song_id = ?", keepSongId, sourceSongId));
        counts.put("history", jdbc.update("UPDATE play_history SET song_id = ? WHERE song_id = ?", keepSongId, sourceSongId));
        counts.put("songFiles", jdbc.update("UPDATE song_files SET song_id = ? WHERE song_id = ?", keepSongId, sourceSongId));
        counts.put("importRecords", jdbc.update("UPDATE media_import_records SET song_id = ? WHERE song_id = ?", keepSongId, sourceSongId));

        jdbc.update("""
                UPDATE ai_analysis_tasks
                SET status = 'merged', error_message = ?, updated_at = now()
                WHERE song_id = ? AND status IN ('pending', 'processing', 'review')
                """, "歌曲已合并到 #" + keepSongId, sourceSongId);
        counts.put("aiTasks", jdbc.update("""
                UPDATE ai_analysis_tasks SET song_id = ?, target_id = ?
                WHERE song_id = ?
                """, keepSongId, keepSongId, sourceSongId));

        jdbc.update("""
                UPDATE songs keep
                SET play_count = keep.play_count + source.play_count,
                    has_vocal_track = keep.has_vocal_track OR source.has_vocal_track,
                    cover_path = COALESCE(keep.cover_path, source.cover_path),
                    lyric_path = COALESCE(keep.lyric_path, source.lyric_path),
                    lyric_type = CASE WHEN keep.lyric_path IS NULL THEN source.lyric_type ELSE keep.lyric_type END,
                    lyric_source = CASE WHEN keep.lyric_path IS NULL THEN source.lyric_source ELSE keep.lyric_source END,
                    updated_at = now()
                FROM songs source
                WHERE keep.id = ? AND source.id = ?
                """, keepSongId, sourceSongId);
        jdbc.update("DELETE FROM songs WHERE id = ?", sourceSongId);

        Long primaryFileId = jdbc.query("""
                SELECT id FROM song_files
                WHERE song_id = ? AND valid = true
                ORDER BY priority DESC, file_size DESC, id ASC LIMIT 1
                """, result -> result.next() ? result.getLong(1) : null, keepSongId);
        return Map.of("status", "merged", "keepSongId", keepSongId, "sourceSongId", sourceSongId,
                "primaryFileId", primaryFileId == null ? 0L : primaryFileId, "migrated", counts);
    }

    private int mergePlaylistSongs(Long keepSongId, Long sourceSongId) {
        int insertedOrUpdated = jdbc.update("""
                INSERT INTO playlist_songs (playlist_id, song_id, sort_order, manual)
                SELECT playlist_id, ?, sort_order, manual FROM playlist_songs WHERE song_id = ?
                ON CONFLICT (playlist_id, song_id) DO UPDATE
                SET sort_order = LEAST(playlist_songs.sort_order, EXCLUDED.sort_order),
                    manual = playlist_songs.manual OR EXCLUDED.manual
                """, keepSongId, sourceSongId);
        jdbc.update("DELETE FROM playlist_songs WHERE song_id = ?", sourceSongId);
        return insertedOrUpdated;
    }

    private int mergeFavorites(Long keepSongId, Long sourceSongId) {
        int inserted = jdbc.update("""
                INSERT INTO favorites (user_id, song_id, created_at)
                SELECT user_id, ?, created_at FROM favorites WHERE song_id = ?
                ON CONFLICT (user_id, song_id) DO NOTHING
                """, keepSongId, sourceSongId);
        jdbc.update("DELETE FROM favorites WHERE song_id = ?", sourceSongId);
        return inserted;
    }
}
