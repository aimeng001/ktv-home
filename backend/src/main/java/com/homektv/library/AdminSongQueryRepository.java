package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamic minimal-WHERE query for admin library pagination.
 * Replaces static multi-OR/EXISTS JPQL with indexed ILIKE predicates
 * and revision-scoped count caching across pagination requests.
 */
@Repository
public class AdminSongQueryRepository {
    private static final int MAX_COUNT_CACHE_ENTRIES = 128;
    private static final long COUNT_CACHE_TTL_MS = 30_000L;
    private static final int QUERY_TIMEOUT_SECONDS = 8;

    private record CountCacheEntry(long total, long catalogRevision, long cachedAtMs) {}

    private final JdbcTemplate jdbc;
    private final SongRepository songRepo;
    private final CatalogRevisionService catalogRevisionService;
    private final Map<String, CountCacheEntry> countCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CountCacheEntry> eldest) {
                    return size() > MAX_COUNT_CACHE_ENTRIES;
                }
            });

    @Autowired
    public AdminSongQueryRepository(JdbcTemplate jdbc,
                                    SongRepository songRepo,
                                    @Autowired(required = false) CatalogRevisionService catalogRevisionService) {
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds != null) {
            JdbcTemplate bounded = new JdbcTemplate(ds);
            bounded.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            this.jdbc = bounded;
        } else {
            this.jdbc = jdbc;
        }
        this.songRepo = songRepo;
        this.catalogRevisionService = catalogRevisionService;
    }

    public Page<Song> searchAdminSongs(String keyword, String type, String source, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String safeKeyword = keyword == null ? "" : keyword.trim();
        String safeType = type == null ? "" : type.trim();
        String safeSource = source == null ? "" : source.trim();

        StringBuilder where = new StringBuilder(
                " WHERE EXISTS (SELECT 1 FROM song_files file WHERE file.song_id = s.id AND file.valid = true)");
        List<Object> params = new ArrayList<>();

        if (!safeKeyword.isEmpty()) {
            String lower = safeKeyword.toLowerCase(Locale.ROOT);
            String lowerLike = escapeLike(lower);
            boolean asciiOnly = isAscii(lower);
            where.append("""
                     AND (
                        LOWER(s.title) LIKE '%' || ? || '%' ESCAPE '\\'
                        OR LOWER(s.artist) LIKE '%' || ? || '%' ESCAPE '\\'""");
            params.add(lowerLike);
            params.add(lowerLike);
            if (asciiOnly) {
                where.append("""
                        
                        OR LOWER(s.title_py) LIKE ? || '%' ESCAPE '\\'
                        OR LOWER(s.title_init) LIKE ? || '%' ESCAPE '\\'
                        OR LOWER(s.artist_py) LIKE ? || '%' ESCAPE '\\'
                        OR LOWER(s.artist_init) LIKE ? || '%' ESCAPE '\\'""");
                params.add(lowerLike);
                params.add(lowerLike);
                params.add(lowerLike);
                params.add(lowerLike);
            }
            where.append(")");
        }

        if (!safeType.isEmpty()) {
            if ("unrecognized".equals(safeType)) {
                where.append(" AND s.status = 'unrecognized'");
            } else {
                where.append(" AND s.media_type = ?");
                params.add(safeType);
            }
        }

        switch (safeSource) {
            case "EXTERNAL_READ_ONLY" -> where.append(
                    " AND EXISTS (SELECT 1 FROM song_files f WHERE f.song_id = s.id AND f.valid = true AND f.file_role = 'EXTERNAL_READ_ONLY')");
            case "UNKNOWN" -> where.append(
                    " AND EXISTS (SELECT 1 FROM song_files f WHERE f.song_id = s.id AND f.valid = true AND f.source_path IS NULL AND f.file_role <> 'EXTERNAL_READ_ONLY')");
            case "COPIED" -> where.append(
                    " AND EXISTS (SELECT 1 FROM song_files f WHERE f.song_id = s.id AND f.valid = true AND f.source_path IS NOT NULL AND f.transcode_required = false)");
            case "TRANSCODED" -> where.append(
                    " AND EXISTS (SELECT 1 FROM song_files f WHERE f.song_id = s.id AND f.valid = true AND f.source_path IS NOT NULL AND f.transcode_required = true)");
            default -> {
                // ignore unknown empty/unmatched source filter gracefully
                if (!safeSource.isEmpty()) {
                    where.append(" AND 1 = 0");
                }
            }
        }

        List<Object> pageParams = new ArrayList<>(params.size() + 2);
        pageParams.addAll(params);
        pageParams.add(safeSize);
        pageParams.add((long) safePage * safeSize);

        List<Long> orderedIds = jdbc.queryForList(
                "SELECT s.id FROM songs s" + where + " ORDER BY s.created_at DESC, s.id DESC LIMIT ? OFFSET ?",
                Long.class,
                pageParams.toArray());

        List<Song> orderedSongs;
        if (orderedIds.isEmpty()) {
            orderedSongs = List.of();
        } else {
            Map<Long, Song> byId = new LinkedHashMap<>();
            songRepo.findAllById(orderedIds).forEach(s -> byId.put(s.getId(), s));
            orderedSongs = new ArrayList<>(orderedIds.size());
            for (Long id : orderedIds) {
                Song song = byId.get(id);
                if (song != null) orderedSongs.add(song);
            }
        }

        long total = resolveTotal(safeKeyword, safeType, safeSource, safePage, safeSize, orderedSongs.size(), where.toString(), params);
        return new PageImpl<>(orderedSongs, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")), total);
    }

    private long resolveTotal(String keyword, String type, String source, int safePage, int safeSize,
                              int pageItemCount, String whereSql, List<Object> params) {
        if (safePage == 0 && pageItemCount < safeSize) {
            return pageItemCount;
        }
        long revision = catalogRevisionService != null ? catalogRevisionService.current() : 0L;
        long nowMs = System.currentTimeMillis();
        String cacheKey = keyword + "|" + type + "|" + source;
        if (safePage > 0) {
            CountCacheEntry cached = countCache.get(cacheKey);
            if (cached != null && cached.catalogRevision() == revision && nowMs - cached.cachedAtMs() < COUNT_CACHE_TTL_MS) {
                return cached.total();
            }
        }
        Long counted = jdbc.queryForObject("SELECT COUNT(*) FROM songs s" + whereSql, Long.class, params.toArray());
        long total = counted == null ? 0L : counted;
        countCache.put(cacheKey, new CountCacheEntry(total, revision, nowMs));
        return total;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return false;
        }
        return true;
    }
}
