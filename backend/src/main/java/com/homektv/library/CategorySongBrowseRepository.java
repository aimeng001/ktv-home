package com.homektv.library;

import com.homektv.common.ArtistCreditParser;
import com.homektv.common.SearchKeyword;
import com.homektv.domain.Song;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamic SQL browse repository for public category/language/tag/word-count song paging.
 * Builds minimal WHERE clauses with only active predicates so PostgreSQL keeps partial
 * indexes (such as V73 idx_songs_ok_title_len_play) instead of falling back to generic plans,
 * and caches repeated page counts across pagination bound to catalog revision.
 */
@Repository
public class CategorySongBrowseRepository {

    private static final int COUNT_CACHE_CAPACITY = 256;
    private static final long COUNT_CACHE_TTL_NANOS = 30_000_000_000L;

    private static final String SELECT_COLUMNS = """
            SELECT s.id, s.catalog_number, s.title, s.artist, s.artist_gender,
                   s.media_type, s.has_vocal_track, s.duration_ms, s.lyric_type,
                   s.cover_path, s.play_count, s.title_py, s.title_init,
                   s.artist_py, s.artist_init, s.vocal_form, s.ai_vocal_form
            FROM songs s
            """;

    private record CountCacheEntry(long total, long expiresAtNanos) {}

    public record BrowseCriteria(
            String artist,
            String artistKey,
            String artistGender,
            String language,
            String tag,
            String vocalForm,
            String rawKeyword,
            String mediaType,
            int wordCount,
            String sort,
            int page,
            int size
    ) {}

    public record BrowseResult(List<Song> songs, long total) {}

    private final JdbcTemplate jdbc;
    private final CatalogRevisionService catalogRevisionService;
    private final Map<String, CountCacheEntry> countCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CountCacheEntry> eldest) {
                    return size() > COUNT_CACHE_CAPACITY;
                }
            });

    @Autowired
    public CategorySongBrowseRepository(
            JdbcTemplate jdbc,
            @Autowired(required = false) CatalogRevisionService catalogRevisionService) {
        this.jdbc = jdbc;
        this.catalogRevisionService = catalogRevisionService;
    }

    public BrowseResult browse(BrowseCriteria criteria) {
        String artist = normalize(criteria.artist());
        String artistKey = ArtistCreditParser.key(criteria.artistKey());
        String artistGender = normalize(criteria.artistGender());
        String language = normalize(criteria.language());
        String tag = normalize(criteria.tag());
        String vocalForm = normalize(criteria.vocalForm());
        String mediaType = normalize(criteria.mediaType()).toUpperCase(Locale.ROOT);
        int wordCount = Math.max(0, criteria.wordCount());
        int safePage = Math.max(0, criteria.page());
        int safeSize = Math.max(1, Math.min(criteria.size(), 200));

        SearchKeyword kw = SearchKeyword.of(criteria.rawKeyword(), SearchKeyword.DEFAULT_MAX_LENGTH);
        String kwLower = kw.lower();
        String kwLike = kw.likePattern();
        long numericId = kwLower.matches("^[0-9]{1,10}$") ? Long.parseLong(kwLower) : 0L;

        StringBuilder where = new StringBuilder("WHERE s.status = 'ok'");
        List<Object> params = new ArrayList<>(12);

        if (!artistKey.isEmpty()) {
            where.append("""
                     AND s.id IN (
                        SELECT sa.song_id FROM song_artists sa
                         WHERE LOWER(sa.artist_key) = LOWER(?)
                        UNION
                        SELECT s2.id FROM songs s2
                         WHERE s2.status = 'ok'
                           AND LOWER(regexp_replace(trim(s2.artist), '[[:space:]]+', '', 'g')) = LOWER(?)
                           AND NOT EXISTS (SELECT 1 FROM song_artists sa2 WHERE sa2.song_id = s2.id)
                    )""");
            params.add(artistKey);
            params.add(artistKey);
        } else if (!artist.isEmpty()) {
            where.append("""
                     AND (LOWER(s.artist) = LOWER(?)
                          OR EXISTS (SELECT 1 FROM song_artists sa
                                      WHERE sa.song_id = s.id AND LOWER(sa.artist_name) = LOWER(?)))""");
            params.add(artist);
            params.add(artist);
        }

        if (!artistGender.isEmpty()) {
            if (!artistKey.isEmpty()) {
                where.append("""
                         AND (
                            EXISTS (
                                SELECT 1 FROM song_artists sa
                                LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                                WHERE sa.song_id = s.id
                                  AND LOWER(sa.artist_key) = LOWER(?)
                                  AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(?)
                            )
                            OR (
                                NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                                AND LOWER(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = LOWER(?)
                                AND LOWER(s.artist_gender) = LOWER(?)
                            )
                        )""");
                params.add(artistKey);
                params.add(artistGender);
                params.add(artistKey);
                params.add(artistGender);
            } else if (!artist.isEmpty()) {
                where.append("""
                         AND (
                            EXISTS (
                                SELECT 1 FROM song_artists sa
                                LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                                WHERE sa.song_id = s.id
                                  AND LOWER(sa.artist_name) = LOWER(?)
                                  AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(?)
                            )
                            OR (
                                NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                                AND LOWER(s.artist) = LOWER(?)
                                AND LOWER(s.artist_gender) = LOWER(?)
                            )
                        )""");
                params.add(artist);
                params.add(artistGender);
                params.add(artist);
                params.add(artistGender);
            } else {
                where.append("""
                         AND (
                            EXISTS (
                                SELECT 1 FROM song_artists sa
                                LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                                WHERE sa.song_id = s.id
                                  AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(?)
                            )
                            OR (
                                NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                                AND LOWER(s.artist_gender) = LOWER(?)
                            )
                        )""");
                params.add(artistGender);
                params.add(artistGender);
            }
        }

        if (!language.isEmpty()) {
            where.append(" AND LOWER(s.language) = LOWER(?)");
            params.add(language);
        }

        if (!vocalForm.isEmpty()) {
            where.append(" AND LOWER(COALESCE(NULLIF(NULLIF(s.vocal_form, ''), '未知'), NULLIF(s.ai_vocal_form, ''), '未知')) = LOWER(?)");
            params.add(vocalForm);
        }

        if (!mediaType.isEmpty()) {
            where.append(" AND s.media_type = ?");
            params.add(mediaType);
        }

        if (!tag.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM song_tag_index sti WHERE sti.song_id = s.id AND sti.tag_lower = LOWER(?))");
            params.add(tag);
        }

        if (!kwLike.isEmpty()) {
            where.append("""
                     AND (
                        s.title ILIKE '%' || ? || '%' ESCAPE '\\'
                        OR LOWER(s.title_init) LIKE ? || '%' ESCAPE '\\'
                        OR LOWER(s.title_py) LIKE ? || '%' ESCAPE '\\'
                        OR s.artist ILIKE '%' || ? || '%' ESCAPE '\\'
                        OR LOWER(s.artist_init) LIKE ? || '%' ESCAPE '\\'
                        OR LOWER(s.artist_py) LIKE ? || '%' ESCAPE '\\'
                        OR (s.catalog_number <> '' AND s.catalog_number = ?)""");
            params.add(kwLike);
            params.add(kwLike);
            params.add(kwLike);
            params.add(kwLike);
            params.add(kwLike);
            params.add(kwLike);
            params.add(kwLower);
            if (numericId > 0) {
                where.append(" OR s.id = ?");
                params.add(numericId);
            }
            where.append(")");
        }

        if (wordCount > 0) {
            where.append(" AND LEAST(char_length(s.title), 6) = LEAST(?, 6)");
            params.add(wordCount);
        }

        String orderBy = orderByClause(criteria.sort());
        List<Object> pageParams = new ArrayList<>(params.size() + 2);
        pageParams.addAll(params);
        pageParams.add(safeSize);
        pageParams.add((long) safePage * safeSize);

        List<Song> rows = jdbc.query(
                SELECT_COLUMNS + where + " " + orderBy + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Song song = new Song();
                    song.setId(rs.getLong("id"));
                    song.setCatalogNumber(rs.getString("catalog_number"));
                    song.setTitle(rs.getString("title"));
                    song.setArtist(rs.getString("artist"));
                    song.setArtistGender(rs.getString("artist_gender"));
                    song.setMediaType(rs.getString("media_type"));
                    song.setHasVocalTrack(rs.getBoolean("has_vocal_track"));
                    song.setDurationMs(rs.getInt("duration_ms"));
                    song.setLyricType(rs.getString("lyric_type"));
                    song.setCoverPath(rs.getString("cover_path"));
                    song.setPlayCount(rs.getInt("play_count"));
                    song.setTitlePy(rs.getString("title_py"));
                    song.setTitleInit(rs.getString("title_init"));
                    song.setArtistPy(rs.getString("artist_py"));
                    song.setArtistInit(rs.getString("artist_init"));
                    song.setVocalForm(rs.getString("vocal_form"));
                    song.setAiVocalForm(rs.getString("ai_vocal_form"));
                    return song;
                },
                pageParams.toArray());

        long total = resolveTotal(where, params, safePage, safeSize, rows.size(),
                artist, artistKey, artistGender, language, tag, vocalForm, kwLower, mediaType, wordCount);
        return new BrowseResult(rows, total);
    }

    private long resolveTotal(StringBuilder where, List<Object> params,
                              int page, int size, int returnedCount,
                              String artist, String artistKey, String artistGender,
                              String language, String tag, String vocalForm,
                              String kwLower, String mediaType, int wordCount) {
        if (page == 0 && returnedCount < size) {
            return returnedCount;
        }
        long revision = currentCatalogRevision();
        String cacheKey = revision + "|" + artist + "|" + artistKey + "|" + artistGender + "|"
                + language + "|" + tag + "|" + vocalForm + "|" + kwLower + "|" + mediaType + "|" + wordCount;
        long now = System.nanoTime();
        // Always refresh total on page 0 so direct writes/edits are immediately reflected,
        // then reuse the cached count on subsequent pages (page > 0) within TTL & same revision.
        if (page > 0) {
            CountCacheEntry cached = countCache.get(cacheKey);
            if (cached != null && now < cached.expiresAtNanos()) {
                return Math.max(cached.total(), (long) page * size + returnedCount);
            }
        }
        Long counted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM songs s " + where,
                Long.class,
                params.toArray());
        long total = counted == null ? 0L : counted;
        countCache.put(cacheKey, new CountCacheEntry(total, now + COUNT_CACHE_TTL_NANOS));
        return total;
    }

    private long currentCatalogRevision() {
        if (catalogRevisionService == null) return 0L;
        try {
            return catalogRevisionService.current();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String orderByClause(String sort) {
        if ("title".equalsIgnoreCase(sort)) {
            return "ORDER BY s.title ASC, s.id ASC";
        }
        if ("new".equalsIgnoreCase(sort)) {
            return "ORDER BY s.created_at DESC, s.id DESC";
        }
        return "ORDER BY s.play_count DESC, s.id ASC";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
