package com.homektv.repo;

import com.homektv.domain.Song;
import com.homektv.library.SearchKeyword;
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
     * One/two-character search is served from the materialized term projection.
     * Keeping this query separate is intentional: the general search query has
     * trigram, array and pinyin branches which PostgreSQL may still plan as
     * heap scans even when the short-term branch is selective.
     */
    String SHORT_SEARCH_SQL = """
            WITH candidate_ids AS (
                SELECT term.song_id,
                       MIN(CASE
                           WHEN term.kind IN ('TITLE', 'TITLE_PY', 'TITLE_INIT') THEN 0
                           WHEN term.kind IN ('ARTIST', 'ARTIST_PY', 'ARTIST_INIT',
                                               'ARTIST_CREDIT', 'ARTIST_CREDIT_PY', 'ARTIST_CREDIT_INIT') THEN 1
                           WHEN term.kind = 'LANGUAGE' THEN 2
                           ELSE 3
                       END) AS term_rank
                FROM song_search_terms term
                JOIN songs s ON s.id = term.song_id
                WHERE term.value_lower = :kw
                  AND s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                GROUP BY term.song_id
            ), ranked_candidates AS (
                SELECT c.song_id, c.term_rank
                FROM candidate_ids c
                JOIN songs s ON s.id = c.song_id
                ORDER BY
                  (LOWER(s.title) = :kw) DESC,
                  (LOWER(s.artist) = :kw) DESC,
                  (s.title ILIKE :likeKw || '%' ESCAPE '\\') DESC,
                  c.term_rank ASC,
                  (s.media_type = 'KTV_VIDEO') DESC,
                  s.play_count DESC,
                  s.id ASC
                LIMIT 2000
            )
            SELECT s.*
            FROM ranked_candidates c
            JOIN songs s ON s.id = c.song_id
            ORDER BY
              (LOWER(s.title) = :kw) DESC,
              (LOWER(s.artist) = :kw) DESC,
              (s.title ILIKE :likeKw || '%' ESCAPE '\\') DESC,
              c.term_rank ASC,
              (s.media_type = 'KTV_VIDEO') DESC,
              s.play_count DESC,
              s.id ASC
            """;
    /** Shared native SQL used by the repository and opt-in PostgreSQL plan tests. */
    String SEARCH_SQL = """
            WITH candidate_ids AS (
                -- 1. 歌名与主要歌手 Trigram 模糊检索（>=3字符走 GIN 索引）
                SELECT s.id AS song_id
                FROM songs s
                WHERE s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                  AND (
                       (char_length(:kw) > 2 AND (
                            s.title ILIKE '%' || :likeKw || '%' ESCAPE '\\'
                         OR s.artist ILIKE '%' || :likeKw || '%' ESCAPE '\\'
                       ))
                  )

                UNION

                -- 2. 歌名/歌手的拼音与首字母匹配（走 text_pattern_ops B-Tree 索引）
                SELECT s.id
                FROM songs s
                WHERE s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                  AND (
                       s.title_init = :kw
                    OR LOWER(s.title_init) LIKE :likeKw || '%' ESCAPE '\\'
                    OR LOWER(s.title_py) LIKE :likeKw || '%' ESCAPE '\\'
                    OR s.artist_init = :kw
                    OR LOWER(s.artist_init) LIKE :likeKw || '%' ESCAPE '\\'
                    OR LOWER(s.artist_py) LIKE :likeKw || '%' ESCAPE '\\'
                  )

                UNION

                -- 3. 多歌手关联表（解决合唱歌曲副歌手检索，走 song_artists 索引）
                SELECT sa.song_id
                FROM song_artists sa
                JOIN songs s ON s.id = sa.song_id
                WHERE s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                  AND (
                       (char_length(:kw) > 2
                            AND sa.artist_name ILIKE '%' || :likeKw || '%' ESCAPE '\\')
                    OR LOWER(sa.artist_init) LIKE :likeKw || '%' ESCAPE '\\'
                    OR LOWER(sa.artist_py) LIKE :likeKw || '%' ESCAPE '\\'
                  )

                UNION

                -- 4. 语种前缀匹配（如 '粤' 匹配 '粤语'，走 text_pattern_ops 索引）
                SELECT s.id
                FROM songs s
                WHERE s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                  AND LOWER(s.language) LIKE :likeKw || '%' ESCAPE '\\'

                UNION

                -- 5. 保留历史的大小写不敏感标签子串语义，但从维护型
                -- projection 读取完整标签值，避免热路径逐行 unnest。
                SELECT term.song_id
                FROM song_search_terms term
                JOIN songs s ON s.id = term.song_id
                WHERE s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
                  AND term.kind = 'TAG'
                  AND char_length(:kw) > 2
                  AND term.value_lower LIKE '%' || :likeKw || '%' ESCAPE '\\'

                UNION

                -- 6. 一/二字符原文子串由维护型 gram 表命中，避免扫描
                -- songs 并避免对 tags 做逐行 unnest。
                SELECT term.song_id
                FROM song_search_terms term
                JOIN songs s ON s.id = term.song_id
                WHERE char_length(:kw) BETWEEN 1 AND 2
                  AND term.value_lower = :kw
                  AND term.kind IN ('TITLE', 'ARTIST', 'LANGUAGE', 'TAG', 'ARTIST_CREDIT')
                  AND s.status = 'ok'
                  AND (:mediaType = '' OR s.media_type = :mediaType)
            )
            SELECT s.*
            FROM candidate_ids c
            JOIN songs s ON s.id = c.song_id
            ORDER BY
              (s.title = :rawKw) DESC,
              (s.artist = :rawKw) DESC,
              (LOWER(s.title) = :kw) DESC,
              (LOWER(s.artist) = :kw) DESC,
              (s.title ILIKE :likeKw || '%' ESCAPE '\\') DESC,
              (s.media_type = 'KTV_VIDEO') DESC,
              similarity(LOWER(s.title), :kw) DESC,
              s.play_count DESC,
              s.id ASC
            """;

    /**
     * 多字段综合搜索，支持中文关键词、全拼和首字母匹配。
     * 采用候选集 UNION 架构；模糊模式由服务层转义，避免用户输入改变 LIKE 语义。
     *
     * Multi-field search supporting Chinese keywords, full pinyin, and initial matching.
     * The service supplies the trimmed raw keyword, a lower-cased keyword,
     * and an independently escaped LIKE pattern. Exact ranking uses the raw
     * and lower values; LIKE branches use the escaped value with an explicit
     * backslash escape character.
     *
     * @param rawKeyword 去除首尾空白但保留大小写的关键词 / trimmed raw keyword
     * @param keyword 小写关键词，用于大小写不敏感比较 / lower-cased keyword
     * @param likeKeyword 已转义的 LIKE 模式片段 / escaped LIKE fragment
     * @param mediaType 媒体类型；空字符串表示全部类型 / media type; empty means all types
     * @param pageable 分页参数 / pagination parameters
     * @return 匹配的歌曲列表 / list of matching songs
     */
    @Query(value = SEARCH_SQL,
            nativeQuery = true)
    List<Song> search(@Param("rawKw") String rawKeyword,
                      @Param("kw") String keyword,
                      @Param("likeKw") String likeKeyword,
                      @Param("mediaType") String mediaType,
                      Pageable pageable);

    /** Indexed one/two-character search backed by song_search_terms. */
    @Query(value = SHORT_SEARCH_SQL, nativeQuery = true)
    List<Song> searchShort(@Param("rawKw") String rawKeyword,
                           @Param("kw") String keyword,
                           @Param("likeKw") String likeKeyword,
                           @Param("mediaType") String mediaType,
                           Pageable pageable);

    /** Source-compatible overload for callers that do not need a type filter. */
    default List<Song> search(String keyword, Pageable pageable) {
        SearchKeyword normalized = SearchKeyword.of(keyword, SearchKeyword.DEFAULT_MAX_LENGTH);
        return search(normalized.raw(), normalized.lower(), normalized.likePattern(), "", pageable);
    }
}
