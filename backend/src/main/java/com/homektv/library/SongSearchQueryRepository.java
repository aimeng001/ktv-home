package com.homektv.library;

import com.homektv.common.SearchKeyword;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic intent-routed PostgreSQL query engine for public song search.
 * Replaces generic multi-OR/14-branch static plans with input-shape routing,
 * predicate elimination, split partial GIN scans, and bounded Top-N pushdown.
 */
@Repository
public class SongSearchQueryRepository {

    private static final int QUERY_TIMEOUT_SECONDS = 6;
    private static final int MIN_BRANCH_CAP = 300;
    private static final int MAX_BRANCH_CAP = 2000;
    private static final int MIN_TRGM_CAP = 600;
    private static final int MAX_TRGM_CAP = 2500;

    private final JdbcTemplate jdbc;
    private final SongRepository songRepo;

    @Autowired
    public SongSearchQueryRepository(JdbcTemplate jdbc, SongRepository songRepo) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource != null) {
            JdbcTemplate bounded = new JdbcTemplate(dataSource);
            bounded.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            this.jdbc = bounded;
        } else {
            this.jdbc = jdbc;
        }
        this.songRepo = songRepo;
    }

    public List<Song> search(SearchKeyword keyword, long numericId, String mediaType, int page, int pageSize) {
        String rawKw = keyword.raw();
        String kw = keyword.lower();
        String likeKw = keyword.likePattern();
        String safeMediaType = mediaType == null ? "" : mediaType.trim();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) safePage * safeSize;

        int codePoints = kw.codePointCount(0, kw.length());
        boolean asciiAlpha = isAsciiAlpha(kw);
        boolean pureDigits = numericId > 0 && kw.matches("^[0-9]{1,10}$");
        boolean containsNonAscii = containsNonAscii(kw);

        int branchCap = Math.min(MAX_BRANCH_CAP, Math.max(MIN_BRANCH_CAP, (safePage + 1) * safeSize * 6));
        int trgmCap = Math.min(MAX_TRGM_CAP, Math.max(MIN_TRGM_CAP, (safePage + 1) * safeSize * 10));

        List<Object> params = new ArrayList<>(32);
        String sql;
        if (codePoints == 1 && asciiAlpha) {
            sql = buildSingleInitialSql(rawKw, kw, likeKw, safeMediaType, safeSize, offset, branchCap, params);
        } else if (codePoints <= 2) {
            sql = buildShortSearchSql(rawKw, kw, likeKw, numericId, pureDigits, containsNonAscii,
                    safeMediaType, safeSize, offset, branchCap, params);
        } else {
            sql = buildGeneralSearchSql(rawKw, kw, likeKw, numericId, pureDigits, containsNonAscii,
                    safeMediaType, safeSize, offset, branchCap, trgmCap, params);
        }

        List<Long> orderedIds = jdbc.queryForList(sql, Long.class, params.toArray());
        return hydrateOrderedSongs(orderedIds);
    }

    private List<Song> hydrateOrderedSongs(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Song> byId = new LinkedHashMap<>(orderedIds.size() * 2);
        songRepo.findAllById(orderedIds).forEach(song -> byId.put(song.getId(), song));
        List<Song> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Song song = byId.get(id);
            if (song != null) {
                ordered.add(song);
            }
        }
        return ordered;
    }

    private String buildSingleInitialSql(String rawKw, String kw, String likeKw, String mediaType,
                                         int size, long offset, int branchCap, List<Object> params) {
        String mediaClause = mediaFilterClause("s", mediaType);
        List<String> branches = new ArrayList<>(5);

        // Tier 0: Exact 1-character title or title_init match (untruncated, fast B-Tree)
        StringBuilder b0 = new StringBuilder("""
                SELECT s.id AS song_id, 0 AS term_rank
                FROM songs s
                WHERE s.status = 'ok'""");
        appendMediaArg(b0, mediaClause, mediaType, params);
        b0.append(" AND (LOWER(s.title) = ? OR LOWER(s.title_init) = ?)");
        params.add(kw);
        params.add(kw);
        branches.add(b0.toString());

        // Tier 1: Hot Top-N title initials via V77 functional first-letter + play_count index
        StringBuilder b1 = new StringBuilder("""
                (SELECT s.id AS song_id, 1 AS term_rank
                FROM songs s
                WHERE s.status = 'ok'
                  AND s.title_init <> ''""");
        appendMediaArg(b1, mediaClause, mediaType, params);
        b1.append("""
                  AND LOWER(left(s.title_init, 1)) = ?
                ORDER BY s.play_count DESC, s.id ASC
                LIMIT ?)""");
        params.add(kw);
        params.add(branchCap);
        branches.add(b1.toString());

        // Tier 2: Hot Top-N artist initials via V77 functional first-letter + play_count index
        StringBuilder b2 = new StringBuilder("""
                (SELECT s.id AS song_id, 2 AS term_rank
                FROM songs s
                WHERE s.status = 'ok'
                  AND s.artist_init <> ''""");
        appendMediaArg(b2, mediaClause, mediaType, params);
        b2.append("""
                  AND LOWER(left(s.artist_init, 1)) = ?
                ORDER BY s.play_count DESC, s.id ASC
                LIMIT ?)""");
        params.add(kw);
        params.add(branchCap);
        branches.add(b2.toString());

        // Tier 3: Collaborative song_artists single initial
        StringBuilder b3 = new StringBuilder("""
                (SELECT sa.song_id, 3 AS term_rank
                FROM song_artists sa
                JOIN songs s ON s.id = sa.song_id
                WHERE s.status = 'ok'
                  AND sa.artist_init <> ''""");
        appendMediaArg(b3, mediaClause, mediaType, params);
        b3.append("""
                  AND LOWER(left(sa.artist_init, 1)) = ?
                ORDER BY s.play_count DESC, s.id ASC
                LIMIT ?)""");
        params.add(kw);
        params.add(branchCap);
        branches.add(b3.toString());

        StringBuilder sql = new StringBuilder("""
                WITH unique_candidates AS (
                    SELECT song_id, MIN(term_rank) AS best_priority
                    FROM (
                """);
        sql.append(String.join("\n    UNION ALL\n", branches));
        sql.append("""
                    ) c
                    GROUP BY song_id
                )
                SELECT s.id
                FROM unique_candidates c
                JOIN songs s ON s.id = c.song_id
                ORDER BY
                  (LOWER(s.title) = ?) DESC,
                  (LOWER(s.title_init) = ?) DESC,
                  (LOWER(s.title) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.title_init) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.artist) = ?) DESC,
                  (LOWER(s.artist_init) = ?) DESC,
                  c.best_priority ASC,
                  CHAR_LENGTH(s.title) ASC,
                  (s.media_type = 'KTV_VIDEO') DESC,
                  s.play_count DESC,
                  s.id ASC
                LIMIT ? OFFSET ?
                """);
        params.add(kw);
        params.add(kw);
        params.add(likeKw);
        params.add(likeKw);
        params.add(kw);
        params.add(kw);
        params.add(size);
        params.add(offset);
        return sql.toString();
    }

    private String buildShortSearchSql(String rawKw, String kw, String likeKw, long numericId,
                                       boolean pureDigits, boolean containsNonAscii, String mediaType,
                                       int size, long offset, int branchCap, List<Object> params) {
        String mediaClause = mediaFilterClause("s", mediaType);
        List<String> branches = new ArrayList<>(12);
        int codePoints = kw.codePointCount(0, kw.length());
        boolean twoCharAsciiAlpha = codePoints == 2 && isAsciiAlpha(kw);
        String firstChar = kw.substring(0, kw.offsetByCodePoints(0, 1));

        if (numericId > 0) {
            StringBuilder idExact = new StringBuilder("""
                    SELECT s.id AS song_id, 0 AS term_rank
                    FROM songs s
                    WHERE s.id = ?
                      AND s.status = 'ok'""");
            params.add(numericId);
            appendMediaArg(idExact, mediaClause, mediaType, params);
            branches.add(idExact.toString());

            StringBuilder catExact = new StringBuilder("""
                    SELECT s.id AS song_id, 0 AS term_rank
                    FROM songs s
                    WHERE s.status = 'ok'""");
            appendMediaArg(catExact, mediaClause, mediaType, params);
            catExact.append(" AND s.catalog_number <> '' AND s.catalog_number = ?");
            params.add(kw);
            branches.add(catExact.toString());
        }

        // Exact title / artist / initials (never truncated)
        StringBuilder exactTitle = new StringBuilder("""
                SELECT s.id AS song_id, 0 AS term_rank
                FROM songs s
                WHERE s.status = 'ok'""");
        appendMediaArg(exactTitle, mediaClause, mediaType, params);
        exactTitle.append(" AND LOWER(s.title) = ?");
        params.add(kw);
        branches.add(exactTitle.toString());

        StringBuilder exactArtist = new StringBuilder("""
                SELECT s.id AS song_id, 3 AS term_rank
                FROM songs s
                WHERE s.status = 'ok'""");
        appendMediaArg(exactArtist, mediaClause, mediaType, params);
        exactArtist.append(" AND LOWER(s.artist) = ?");
        params.add(kw);
        branches.add(exactArtist.toString());

        if (!containsNonAscii) {
            StringBuilder exactInit = new StringBuilder("""
                    SELECT s.id AS song_id, 0 AS term_rank
                    FROM songs s
                    WHERE s.status = 'ok'""");
            appendMediaArg(exactInit, mediaClause, mediaType, params);
            exactInit.append(" AND LOWER(s.title_init) = ?");
            params.add(kw);
            branches.add(exactInit.toString());

            StringBuilder exactArtistInit = new StringBuilder("""
                    SELECT s.id AS song_id, 3 AS term_rank
                    FROM songs s
                    WHERE s.status = 'ok'""");
            appendMediaArg(exactArtistInit, mediaClause, mediaType, params);
            exactArtistInit.append(" AND LOWER(s.artist_init) = ?");
            params.add(kw);
            branches.add(exactArtistInit.toString());
        }

        // Ordered Top-N prefix branches so high-fanout queries preserve hottest songs.
        if (containsNonAscii) {
            branches.add(cappedSongsFirstCharPrefixBranch("s.title", 1, firstChar, likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsFirstCharPrefixBranch("s.artist", 3, firstChar, likeKw, mediaClause, mediaType, branchCap, params));
        } else {
            branches.add(cappedSongsPrefixBranch("LOWER(s.title)", 1, likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsPrefixBranch("LOWER(s.artist)", 3, likeKw, mediaClause, mediaType, branchCap, params));
            if (twoCharAsciiAlpha) {
                branches.add(cappedTwoCharInitPrefixBranch("title_init", 1, kw, mediaClause, mediaType, branchCap, params));
                branches.add(cappedTwoCharInitPrefixBranch("artist_init", 4, kw, mediaClause, mediaType, branchCap, params));
            } else {
                branches.add(cappedSongsPrefixBranch("LOWER(s.title_init)", 1, likeKw, mediaClause, mediaType, branchCap, params));
                branches.add(cappedSongsPrefixBranch("LOWER(s.artist_init)", 4, likeKw, mediaClause, mediaType, branchCap, params));
            }
            branches.add(cappedSongsPrefixBranch("LOWER(s.title_py)", 2, likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsPrefixBranch("LOWER(s.artist_py)", 5, likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongArtistPrefixBranch("LOWER(sa.artist_init)", 6, likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongArtistPrefixBranch("LOWER(sa.artist_py)", 7, likeKw, mediaClause, mediaType, branchCap, params));
        }

        // Indexed 1-2 char gram lookup via V47 covering index (capped by rank + play_count)
        if (!pureDigits || kw.length() <= 2) {
            StringBuilder termBranch = new StringBuilder("""
                    (SELECT term.song_id,
                           CASE
                               WHEN term.kind IN ('TITLE', 'TITLE_PY', 'TITLE_INIT') THEN 10
                               WHEN term.kind IN ('ARTIST', 'ARTIST_PY', 'ARTIST_INIT',
                                                  'ARTIST_CREDIT', 'ARTIST_CREDIT_PY', 'ARTIST_CREDIT_INIT') THEN 11
                               WHEN term.kind IN ('LANGUAGE', 'ALIAS') THEN 12
                               ELSE 13
                           END AS term_rank
                    FROM song_search_terms term
                    JOIN songs s ON s.id = term.song_id
                    WHERE term.value_lower = ?
                      AND s.status = 'ok'""");
            params.add(kw);
            appendMediaArg(termBranch, mediaClause, mediaType, params);
            termBranch.append("\n                    ORDER BY term_rank ASC, s.play_count DESC, term.song_id ASC LIMIT ?)");
            params.add(branchCap);
            branches.add(termBranch.toString());
        }

        StringBuilder sql = new StringBuilder("""
                WITH candidate_ids AS (
                    SELECT song_id, MIN(term_rank) AS term_rank
                    FROM (
                """);
        sql.append(String.join("\n    UNION ALL\n", branches));
        sql.append("""
                    ) combined
                    GROUP BY song_id
                )
                SELECT s.id
                FROM candidate_ids c
                JOIN songs s ON s.id = c.song_id
                ORDER BY
                """);
        if (numericId > 0) {
            sql.append("  (s.id = ?) DESC,\n");
            sql.append("  (s.catalog_number <> '' AND s.catalog_number = ?) DESC,\n");
            params.add(numericId);
            params.add(kw);
        }
        sql.append("""
                  (LOWER(s.title) = ?) DESC,
                  (LOWER(s.title) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.artist) = ?) DESC,
                """);
        params.add(kw);
        params.add(likeKw);
        params.add(kw);
        if (!containsNonAscii) {
            sql.append("""
                      (LOWER(s.title_init) = ?) DESC,
                      (LOWER(s.title_init) LIKE ? || '%' ESCAPE '\\') DESC,
                      (LOWER(s.title_py) LIKE ? || '%' ESCAPE '\\') DESC,
                      (LOWER(s.artist_init) = ?) DESC,
                    """);
            params.add(kw);
            params.add(likeKw);
            params.add(likeKw);
            params.add(kw);
        }
        sql.append("""
                  c.term_rank ASC,
                  CHAR_LENGTH(s.title) ASC,
                  (s.media_type = 'KTV_VIDEO') DESC,
                  s.play_count DESC,
                  s.id ASC
                LIMIT ? OFFSET ?
                """);
        params.add(size);
        params.add(offset);
        return sql.toString();
    }

    private String buildGeneralSearchSql(String rawKw, String kw, String likeKw, long numericId,
                                         boolean pureDigits, boolean containsNonAscii,
                                         String mediaType, int size, long offset,
                                         int branchCap, int trgmCap, List<Object> params) {
        String mediaClause = mediaFilterClause("s", mediaType);
        List<String> branches = new ArrayList<>(14);

        // Exact numeric ID & catalog number (only when pure/numeric)
        if (numericId > 0) {
            StringBuilder idExact = new StringBuilder("""
                    SELECT s.id AS song_id
                    FROM songs s
                    WHERE s.id = ?
                      AND s.status = 'ok'""");
            params.add(numericId);
            appendMediaArg(idExact, mediaClause, mediaType, params);
            branches.add(idExact.toString());

            StringBuilder catExact = new StringBuilder("""
                    SELECT s.id AS song_id
                    FROM songs s
                    WHERE s.status = 'ok'""");
            appendMediaArg(catExact, mediaClause, mediaType, params);
            catExact.append(" AND s.catalog_number <> '' AND s.catalog_number = ?");
            params.add(kw);
            branches.add(catExact.toString());
        }

        // Exact title / title_init / artist / artist_init (untruncated B-Tree)
        StringBuilder exactMatches = new StringBuilder("""
                SELECT s.id AS song_id
                FROM songs s
                WHERE s.status = 'ok'""");
        appendMediaArg(exactMatches, mediaClause, mediaType, params);
        exactMatches.append(" AND (LOWER(s.title) = ? OR LOWER(s.artist) = ?");
        params.add(kw);
        params.add(kw);
        if (!containsNonAscii) {
            exactMatches.append(" OR LOWER(s.title_init) = ? OR LOWER(s.artist_init) = ?");
            params.add(kw);
            params.add(kw);
        }
        exactMatches.append(")");
        branches.add(exactMatches.toString());

        // Fast prefix B-Tree branches
        branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.title)", likeKw, mediaClause, mediaType, branchCap, params));
        branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.artist)", likeKw, mediaClause, mediaType, branchCap, params));
        branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.language)", likeKw, mediaClause, mediaType, branchCap, params));

        // Pinyin & initials B-Tree prefix branches (only when query can match ASCII pinyin/initials)
        if (!containsNonAscii) {
            branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.title_init)", likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.title_py)", likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.artist_init)", likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongsPrefixBranchNoRank("LOWER(s.artist_py)", likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongArtistPrefixBranchNoRank("LOWER(sa.artist_init)", likeKw, mediaClause, mediaType, branchCap, params));
            branches.add(cappedSongArtistPrefixBranchNoRank("LOWER(sa.artist_py)", likeKw, mediaClause, mediaType, branchCap, params));
        }

        // Substring pg_trgm branches (skip for pure numeric catalog lookups unless short year-like query)
        if (!pureDigits || kw.length() <= 4) {
            branches.add(cappedTrgmSongsBranch("LOWER(s.title)", likeKw, mediaClause, mediaType, trgmCap, params));
            branches.add(cappedTrgmSongsBranch("LOWER(s.artist)", likeKw, mediaClause, mediaType, trgmCap, params));

            StringBuilder saTrgm = new StringBuilder("""
                    (SELECT sa.song_id
                    FROM song_artists sa
                    JOIN songs s ON s.id = sa.song_id
                    WHERE s.status = 'ok'""");
            appendMediaArg(saTrgm, mediaClause, mediaType, params);
            saTrgm.append("""
                      AND LOWER(sa.artist_name) LIKE '%' || ? || '%' ESCAPE '\\'
                    ORDER BY s.play_count DESC, s.id ASC
                    LIMIT ?)""");
            params.add(likeKw);
            params.add(trgmCap);
            branches.add(saTrgm.toString());

            // Split TAG & ALIAS partial GIN scans so PostgreSQL uses V40 / V71 partial indexes directly
            branches.add(cappedTermTrgmBranch("TAG", likeKw, mediaClause, mediaType, trgmCap, params));
            branches.add(cappedTermTrgmBranch("ALIAS", likeKw, mediaClause, mediaType, trgmCap, params));
        }

        boolean skipSimilarity = isAsciiAlnum(kw);
        StringBuilder sql = new StringBuilder("""
                WITH candidate_ids AS (
                    SELECT DISTINCT song_id
                    FROM (
                """);
        sql.append(String.join("\n    UNION ALL\n", branches));
        sql.append("""
                    ) raw_candidates
                )
                SELECT s.id
                FROM candidate_ids c
                JOIN songs s ON s.id = c.song_id
                ORDER BY
                """);
        if (numericId > 0) {
            sql.append("  (s.id = ?) DESC,\n");
            sql.append("  (s.catalog_number <> '' AND s.catalog_number = ?) DESC,\n");
            params.add(numericId);
            params.add(kw);
        }
        sql.append("""
                  (s.title = ?) DESC,
                  (s.artist = ?) DESC,
                  (LOWER(s.title) = ?) DESC,
                  (LOWER(s.title_init) = ?) DESC,
                  (LOWER(s.title) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.title_init) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.title_py) LIKE ? || '%' ESCAPE '\\') DESC,
                  (LOWER(s.artist) = ?) DESC,
                  (LOWER(s.artist_init) = ?) DESC,
                  CHAR_LENGTH(s.title) ASC,
                  (s.media_type = 'KTV_VIDEO') DESC,
                """);
        params.add(rawKw);
        params.add(rawKw);
        params.add(kw);
        params.add(kw);
        params.add(likeKw);
        params.add(likeKw);
        params.add(likeKw);
        params.add(kw);
        params.add(kw);
        if (!skipSimilarity) {
            sql.append("  similarity(LOWER(s.title), ?) DESC,\n");
            params.add(kw);
        }
        sql.append("""
                  s.play_count DESC,
                  s.id ASC
                LIMIT ? OFFSET ?
                """);
        params.add(size);
        params.add(offset);
        return sql.toString();
    }

    private static String cappedTwoCharInitPrefixBranch(String column, int rank, String twoCharLower, String mediaClause,
                                                        String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT s.id AS song_id, ").append(rank)
                .append(" AS term_rank FROM songs s WHERE s.status = 'ok' AND char_length(s.")
                .append(column).append(") >= 2");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND LOWER(left(s.").append(column).append(", 2)) = ? ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(twoCharLower);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedSongsFirstCharPrefixBranch(String column, int rank, String firstChar, String likeKw,
                                                         String mediaClause, String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT s.id AS song_id, ").append(rank)
                .append(" AS term_rank FROM songs s WHERE s.status = 'ok' AND ").append(column).append(" <> ''");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND LOWER(left(").append(column).append(", 1)) = ? AND LOWER(").append(column)
                .append(") LIKE ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(firstChar);
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedSongsPrefixBranch(String expr, int rank, String likeKw, String mediaClause,
                                                String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT s.id AS song_id, ").append(rank)
                .append(" AS term_rank FROM songs s WHERE s.status = 'ok'");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND ").append(expr).append(" LIKE ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedSongArtistPrefixBranch(String expr, int rank, String likeKw, String mediaClause,
                                                     String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT sa.song_id, ").append(rank)
                .append(" AS term_rank FROM song_artists sa JOIN songs s ON s.id = sa.song_id WHERE s.status = 'ok'");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND ").append(expr).append(" LIKE ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedSongsPrefixBranchNoRank(String expr, String likeKw, String mediaClause,
                                                      String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT s.id AS song_id FROM songs s WHERE s.status = 'ok'");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND ").append(expr).append(" LIKE ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedSongArtistPrefixBranchNoRank(String expr, String likeKw, String mediaClause,
                                                           String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT sa.song_id FROM song_artists sa JOIN songs s ON s.id = sa.song_id WHERE s.status = 'ok'");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND ").append(expr).append(" LIKE ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedTrgmSongsBranch(String expr, String likeKw, String mediaClause,
                                                String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("(SELECT s.id AS song_id FROM songs s WHERE s.status = 'ok'");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND ").append(expr).append(" LIKE '%' || ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String cappedTermTrgmBranch(String kind, String likeKw, String mediaClause,
                                               String mediaType, int cap, List<Object> params) {
        StringBuilder sb = new StringBuilder("""
                (SELECT term.song_id
                FROM song_search_terms term
                JOIN songs s ON s.id = term.song_id
                WHERE s.status = 'ok'""");
        appendMediaArg(sb, mediaClause, mediaType, params);
        sb.append(" AND term.kind = ? AND term.value_lower LIKE '%' || ? || '%' ESCAPE '\\' ORDER BY s.play_count DESC, s.id ASC LIMIT ?)");
        params.add(kind);
        params.add(likeKw);
        params.add(cap);
        return sb.toString();
    }

    private static String mediaFilterClause(String alias, String mediaType) {
        return mediaType.isEmpty() ? "" : " AND " + alias + ".media_type = ?";
    }

    private static void appendMediaArg(StringBuilder sb, String mediaClause, String mediaType, List<Object> params) {
        if (!mediaClause.isEmpty()) {
            sb.append(mediaClause);
            params.add(mediaType);
        }
    }

    private static boolean isAsciiAlpha(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'a' || ch > 'z') return false;
        }
        return true;
    }

    private static boolean isAsciiAlnum(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'))) return false;
        }
        return true;
    }

    private static boolean containsNonAscii(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return true;
        }
        return false;
    }
}
