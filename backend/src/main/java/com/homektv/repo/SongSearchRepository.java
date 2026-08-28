package com.homektv.repo;

import com.homektv.domain.Song;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 综合搜索查询（P1.6，详设§10）。
 * 支持中文/全拼/首字母混合，必须覆盖 title/artist 的拼音与首字母四类条件，
 * 否则「zjl→周杰伦」（歌手首字母）会失效。
 * 排序：完全匹配 &gt; 前缀 &gt; 模糊，KTV 版优先，再按点唱量。
 *
 * Comprehensive search query (P1.6, Detailed Design §10).
 * Supports mixed Chinese, full-pinyin, and initial-character search.
 * Must cover four types of conditions for title/artist pinyin and initials;
 * otherwise searches like "zjl→周杰伦" (artist initials) would fail.
 * Sort order: exact match &gt; prefix match &gt; fuzzy match,
 * KTV version preferred, then by play count.
 */
public interface SongSearchRepository extends JpaRepository<Song, Long> {

    /**
     * 多字段综合搜索，支持中文关键词、全拼和首字母匹配。
     * 排序优先级：完全匹配 &gt; 前缀匹配 &gt; 模糊匹配 &gt; KTV 版优先 &gt; 相似度 &gt; 点唱量。
     *
     * Multi-field search supporting Chinese keywords, full pinyin, and initial matching.
     * Sort priority: exact match &gt; prefix match &gt; fuzzy match &gt;
     * KTV version preferred &gt; similarity &gt; play count.
     *
     * @param keyword 搜索关键词 / search keyword
     * @param pageable 分页参数 / pagination parameters
     * @return 匹配的歌曲列表 / list of matching songs
     */
    @Query(value = """
            SELECT * FROM songs
            WHERE status = 'ok' AND (
                  title ILIKE '%' || :kw || '%'
               OR artist ILIKE '%' || :kw || '%'
               OR title_init = :kw
               OR title_py LIKE :kw || '%'
               OR artist_init = :kw
               OR artist_py LIKE :kw || '%'
               OR artist_init LIKE '%' || :kw || '%'
               OR artist_py LIKE '%' || :kw || '%'
               OR language ILIKE '%' || :kw || '%'
               OR EXISTS (
                    SELECT 1
                    FROM song_artists sa
                    WHERE sa.song_id = songs.id
                      AND (
                           sa.artist_name ILIKE '%' || :kw || '%'
                        OR sa.artist_init LIKE '%' || :kw || '%'
                        OR sa.artist_py LIKE '%' || :kw || '%'
                      )
               )
               OR EXISTS (
                    SELECT 1
                    FROM unnest(tags) AS tag
                    WHERE tag ILIKE '%' || :kw || '%'
               )
            )
            ORDER BY
              (title = :kw) DESC,
              (artist = :kw) DESC,
              (title ILIKE :kw || '%') DESC,
              (media_type = 'KTV_VIDEO') DESC,
              similarity(title, :kw) DESC,
              play_count DESC
            """,
            nativeQuery = true)
    List<Song> search(@Param("kw") String keyword, Pageable pageable);
}
