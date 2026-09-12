package com.homektv.repo;

import com.homektv.domain.PlaylistSong;
import com.homektv.domain.PlaylistSongId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Collection;

/**
 * 播放列表歌曲仓库接口，操作 playlist_songs 数据表。
 *
 * Repository interface for playlist songs, operating on the playlist_songs table.
 */
public interface PlaylistSongRepository extends JpaRepository<PlaylistSong, PlaylistSongId> {
    List<PlaylistSong> findByPlaylistIdOrderBySortOrder(Long playlistId);
    List<PlaylistSong> findByPlaylistIdInOrderByPlaylistIdAscSortOrderAsc(Collection<Long> playlistIds);
    void deleteByPlaylistIdAndManualFalse(Long playlistId);
    void deleteByPlaylistIdAndSongId(Long playlistId, Long songId);

    /**
     * 手动插入歌曲到播放列表，若已存在则忽略（INSERT ... ON CONFLICT DO NOTHING）。
     *
     * Manually insert a song into the playlist, ignoring if already present
     * (INSERT ... ON CONFLICT DO NOTHING).
     *
     * @param playlistId 播放列表ID / playlist ID
     * @param songId     歌曲ID / song ID
     * @param sortOrder  排序序号 / sort order
     * @return 影响行数 / number of rows affected
     */
    @Modifying
    @Query(value = "INSERT INTO playlist_songs (playlist_id, song_id, sort_order, manual) " +
            "VALUES (:playlistId, :songId, :sortOrder, true) " +
            "ON CONFLICT (playlist_id, song_id) DO NOTHING", nativeQuery = true)
    int insertManualIfAbsent(Long playlistId, Long songId, int sortOrder);

    /**
     * 对播放列表加排他事务锁（pg_advisory_xact_lock），防止并发修改。
     *
     * Acquires an exclusive transaction-level advisory lock on the playlist
     * to prevent concurrent modification.
     *
     * @param playlistId 播放列表ID / playlist ID
     */
    @Query(value = "SELECT pg_advisory_xact_lock(-CAST(:playlistId AS bigint))", nativeQuery = true)
    void lockPlaylist(Long playlistId);
}
