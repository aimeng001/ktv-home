package com.homektv.library;
import com.homektv.common.ArtistKind;
import com.homektv.common.CatalogCacheEpoch;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refreshes and serves the bounded public artist directory projection.
 * The refresh scans catalog tables once after ingestion; public requests only
 * read the indexed projection and never repeat the full song credit aggregate.
 */
@Service
public class ArtistDirectoryProjectionService {
    static final String ACTIVE_PROJECTION = "active";

    static final String REFRESH_SQL = """
            WITH credit_rows AS (
                SELECT s.id AS song_id,
                       sa.artist_key,
                       trim(sa.artist_name) AS artist_name,
                       COALESCE(NULLIF(sa.artist_init, ''), s.artist_init) AS artist_init,
                       COALESCE(NULLIF(sa.artist_py, ''), s.artist_py) AS artist_py,
                       s.artist_gender,
                       COUNT(*) OVER (PARTITION BY s.id) AS credit_count
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = 'ok' AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name,
                       s.artist_init AS artist_init,
                       s.artist_py AS artist_py,
                       s.artist_gender,
                       1 AS credit_count
                FROM songs s
                WHERE s.status = 'ok'
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(artist_name) AS name,
                       min(NULLIF(artist_init, '')) AS artist_init,
                       min(NULLIF(artist_py, '')) AS artist_py,
                       COUNT(DISTINCT song_id) AS song_count
                FROM credit_rows
                GROUP BY artist_key
            ), inferred_gender AS (
                SELECT artist_key,
                       min(artist_gender) AS gender
                FROM credit_rows
                WHERE credit_count = 1
                  AND artist_gender IN ('男歌手', '女歌手', '组合')
                GROUP BY artist_key
                HAVING count(DISTINCT artist_gender) = 1
            )
            INSERT INTO artist_directory_stats(
                artist_key, name, initial, gender, song_count, reviewed,
                artist_kind, avatar_path, artist_init, artist_py, region, updated_at)
            SELECT a.artist_key,
                   a.name,
                   CASE WHEN COALESCE(a.artist_init, '') = '' THEN '#'
                        WHEN upper(left(a.artist_init, 1)) IN ('A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z')
                             THEN upper(left(a.artist_init, 1))
                        ELSE '#' END,
                   CASE WHEN a.name IN ('群星', '多人', 'Various Artists') THEN '组合'
                        WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous') THEN '未知'
                        ELSE COALESCE(NULLIF(p.gender, '未知'), ref.gender, inferred.gender, '未知') END,
                   a.song_count,
                   COALESCE(
                       (p.gender_status = 'MANUAL' AND NULLIF(p.gender, '未知') IS NOT NULL)
                       OR (NULLIF(p.gender, '未知') IS NOT NULL)
                       OR (ref.gender IS NOT NULL)
                       OR (inferred.gender IS NOT NULL)
                       OR (a.name IN ('群星', '多人', 'Various Artists'))
                       OR (lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous'))
                       OR (a.song_count <= 2),
                       false),
                   COALESCE(p.artist_kind,
                            CASE WHEN a.name IN ('群星', '多人', 'Various Artists') THEN 'VARIOUS'
                                 WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous')
                                      THEN 'UNATTRIBUTED'
                                 ELSE 'PERSON' END),
                   p.avatar_path,
                   COALESCE(a.artist_init, ''),
                   COALESCE(a.artist_py, ''),
                   CASE WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous') THEN ''
                        WHEN a.name IN ('群星', '多人', 'Various Artists') THEN ''
                        ELSE COALESCE(ref.region, '') END,
                   now()
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            LEFT JOIN active_iktv_artist_regions ref ON ref.artist_key = a.artist_key
            LEFT JOIN inferred_gender inferred ON inferred.artist_key = a.artist_key
            ON CONFLICT (artist_key) DO UPDATE SET
                name = EXCLUDED.name,
                initial = EXCLUDED.initial,
                gender = EXCLUDED.gender,
                song_count = EXCLUDED.song_count,
                reviewed = EXCLUDED.reviewed,
                artist_kind = EXCLUDED.artist_kind,
                avatar_path = EXCLUDED.avatar_path,
                artist_init = EXCLUDED.artist_init,
                artist_py = EXCLUDED.artist_py,
                region = EXCLUDED.region,
                updated_at = EXCLUDED.updated_at
            WHERE (artist_directory_stats.name,
                   artist_directory_stats.initial,
                   artist_directory_stats.gender,
                   artist_directory_stats.song_count,
                   artist_directory_stats.reviewed,
                   artist_directory_stats.artist_kind,
                   artist_directory_stats.avatar_path,
                   artist_directory_stats.artist_init,
                   artist_directory_stats.artist_py,
                   artist_directory_stats.region)
               IS DISTINCT FROM
                  (EXCLUDED.name,
                   EXCLUDED.initial,
                   EXCLUDED.gender,
                   EXCLUDED.song_count,
                   EXCLUDED.reviewed,
                   EXCLUDED.artist_kind,
                   EXCLUDED.avatar_path,
                   EXCLUDED.artist_init,
                   EXCLUDED.artist_py,
                   EXCLUDED.region)
            """;

    static final String PRUNE_STALE_SQL = """
            DELETE FROM artist_directory_stats s
            WHERE NOT EXISTS (
                SELECT 1
                FROM song_artists sa
                JOIN songs sg ON sg.id = sa.song_id
                WHERE sg.status = 'ok'
                  AND trim(sa.artist_name) <> ''
                  AND sa.artist_key = s.artist_key
            )
              AND NOT EXISTS (
                SELECT 1
                FROM songs sg
                WHERE sg.status = 'ok'
                  AND sg.artist IS NOT NULL
                  AND trim(sg.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa2 WHERE sa2.song_id = sg.id)
                  AND lower(regexp_replace(trim(sg.artist), '[[:space:]]+', '', 'g')) = s.artist_key
            )
            """;

    private static final long METADATA_CACHE_TTL_NANOS = 15_000_000_000L;
    private static final long PAGE_CACHE_TTL_NANOS = 45_000_000_000L;
    private static final long INITIALS_CACHE_TTL_NANOS = 60_000_000_000L;
    private static final int PAGE_CACHE_CAPACITY = 256;
    private static final int INITIALS_CACHE_CAPACITY = 64;
    private static final int QUERY_TIMEOUT_SECONDS = 6;
    private volatile long readyCachedUntilNanos;
    private volatile Boolean cachedRegionRef;
    private volatile long regionRefCachedUntilNanos;

    private record CachedProjectionPage(ProjectionPage page, long expiresAtNanos) {}
    private record CachedInitials(List<String> initials, long expiresAtNanos) {}

    private final JdbcTemplate jdbc;
    private final JdbcTemplate queryJdbc;
    private final TransactionTemplate refreshTransaction;
    private final Runnable beforeRefreshTaskCompletion;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "artist-directory-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicLong projectionEpoch = new AtomicLong(1L);
    private final Object refreshLock = new Object();
    private final Map<String, CachedProjectionPage> pageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedProjectionPage> eldest) {
                    return size() > PAGE_CACHE_CAPACITY;
                }
            });
    private final Map<String, CachedInitials> initialsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedInitials> eldest) {
                    return size() > INITIALS_CACHE_CAPACITY;
                }
            });

    public ArtistDirectoryProjectionService(JdbcTemplate jdbc) {
        this(jdbc, null, () -> {});
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtistDirectoryProjectionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, () -> {});
    }

    ArtistDirectoryProjectionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                     Runnable beforeRefreshTaskCompletion) {
        this.jdbc = jdbc;
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds != null) {
            JdbcTemplate bounded = new JdbcTemplate(ds);
            bounded.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            this.queryJdbc = bounded;
        } else {
            this.queryJdbc = jdbc;
        }
        this.refreshTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.beforeRefreshTaskCompletion = beforeRefreshTaskCompletion;
    }

    /** Defers single-artist avatar projection update until the surrounding transaction commits. */
    public void updateAvatarPathAfterCommit(String artistKey, String avatarPath) {
        if (artistKey == null || artistKey.isBlank()) return;
        Runnable action = () -> updateAvatarPathNow(artistKey, avatarPath);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void updateAvatarPathNow(String artistKey, String avatarPath) {
        try {
            int updated = jdbc.update(
                    "UPDATE artist_directory_stats SET avatar_path=?, updated_at=now() WHERE artist_key=?",
                    avatarPath, artistKey);
            projectionEpoch.incrementAndGet();
            if (updated == 0) {
                requestRefresh();
            }
        } catch (DataAccessException failure) {
            requestRefresh();
        }
    }

    /** Defers single-artist gender/review projection update until the surrounding transaction commits. */
    public void updateGenderAfterCommit(String artistKey, String gender, boolean reviewed) {
        if (artistKey == null || artistKey.isBlank()) return;
        String effectiveGender = gender == null || gender.isBlank() ? "未知" : gender.trim();
        boolean effectiveReviewed = reviewed && !"未知".equals(effectiveGender);
        Runnable action = () -> updateGenderNow(artistKey, effectiveGender, effectiveReviewed);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void updateGenderNow(String artistKey, String gender, boolean reviewed) {
        try {
            int updated = jdbc.update(
                    "UPDATE artist_directory_stats SET gender=?, reviewed=?, updated_at=now() WHERE artist_key=?",
                    gender, reviewed, artistKey);
            projectionEpoch.incrementAndGet();
            if (updated == 0) {
                requestRefresh();
            }
        } catch (DataAccessException failure) {
            requestRefresh();
        }
    }

    /** Defers mutation-triggered refresh until the surrounding transaction commits. */
    public void requestRefreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { requestRefresh(); }
            });
        } else {
            requestRefresh();
        }
    }

    /** Coalesces AI/profile mutations so a large projection is refreshed at most once per wave. */
    public void requestRefresh() {
        refreshRequested.set(true);
        if (!refreshRunning.compareAndSet(false, true)) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    do {
                        refreshRequested.set(false);
                        try {
                            refresh();
                        } catch (RuntimeException failure) {
                            refreshRequested.set(false);
                            return;
                        }
                    } while (refreshRequested.get());
                    beforeRefreshTaskCompletion.run();
                } finally {
                    refreshRunning.set(false);
                    if (refreshRequested.get()) requestRefresh();
                }
            });
        } catch (RejectedExecutionException failure) {
            refreshRunning.set(false);
        }
    }
    public void refresh() {
        synchronized (refreshLock) {
            if (refreshTransaction == null) {
                refreshInternal();
            } else {
                refreshTransaction.executeWithoutResult(status -> refreshInternal());
            }
        }
    }

    private void refreshInternal() {
        jdbc.update(REFRESH_SQL);
        jdbc.update(PRUNE_STALE_SQL);
        jdbc.update("""
                INSERT INTO artist_directory_projection_state(projection_key, refreshed_at)
                VALUES (?, now())
                ON CONFLICT (projection_key) DO UPDATE SET refreshed_at = EXCLUDED.refreshed_at
                """, ACTIVE_PROJECTION);
        projectionEpoch.incrementAndGet();
        readyCachedUntilNanos = System.nanoTime() + METADATA_CACHE_TTL_NANOS;
        regionRefCachedUntilNanos = 0L;
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!isReady()) {
            requestRefresh();
        }
    }
    public Optional<ProjectionPage> page(String gender, String initial, int page, int size) {
        return page(gender, initial, "", page, size);
    }

    public Optional<ProjectionPage> page(String gender, String initial, String region, int page, int size) {
        return page(gender, initial, region, "", page, size);
    }

    public Optional<ProjectionPage> page(String gender, String initial, String region, String keyword,
                                         int page, int size) {
        if (!isReady()) return Optional.empty();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String safeGender = normalize(gender);
        String safeInitial = normalize(initial).toUpperCase(Locale.ROOT);
        String safeRegion = normalize(region);
        String safeKeyword = normalize(keyword);
        if ("热门".equals(safeInitial)) safeInitial = "";

        long cacheRevision = projectionEpoch.get() ^ (CatalogCacheEpoch.current() << 20);
        String cacheKey = cacheRevision + "|" + safeGender + "|" + safeInitial + "|" + safeRegion
                + "|" + safeKeyword + "|" + safePage + "|" + safeSize;
        long now = System.nanoTime();
        CachedProjectionPage cached = pageCache.get(cacheKey);
        if (cached != null && now < cached.expiresAtNanos()) {
            return Optional.of(cached.page());
        }

        boolean hashInitial = "#".equals(safeInitial);
        StringBuilder where = new StringBuilder();
        List<Object> countParams = new ArrayList<>(8);
        if (!safeGender.isEmpty()) {
            where.append(where.isEmpty() ? "WHERE " : " AND ").append("directory.gender = ?");
            countParams.add(safeGender);
        }
        if (hashInitial) {
            where.append(where.isEmpty() ? "WHERE " : " AND ")
                    .append("(COALESCE(directory.initial, '') = '' OR directory.initial NOT IN ('A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z'))");
        } else if (!safeInitial.isEmpty()) {
            where.append(where.isEmpty() ? "WHERE " : " AND ").append("directory.initial = ?");
            countParams.add(safeInitial);
        }
        if (!safeRegion.isEmpty()) {
            where.append(where.isEmpty() ? "WHERE " : " AND ").append("directory.region = ?");
            countParams.add(safeRegion);
        }
        if (!safeKeyword.isEmpty()) {
            String lower = safeKeyword.toLowerCase(Locale.ROOT);
            String lowerLike = escapeLike(lower);
            boolean asciiOnly = isAscii(lower);
            where.append(where.isEmpty() ? "WHERE " : " AND ")
                    .append("(LOWER(directory.name) LIKE '%' || ? || '%' ESCAPE '\\'");
            countParams.add(lowerLike);
            if (asciiOnly) {
                where.append(" OR LOWER(directory.artist_init) LIKE ? || '%' ESCAPE '\\'")
                        .append(" OR LOWER(directory.artist_py) LIKE ? || '%' ESCAPE '\\'");
                countParams.add(lowerLike);
                countParams.add(lowerLike);
            }
            where.append(")");
        }
        String whereSql = where.isEmpty() ? "" : " " + where;

        List<Object> rowParams = new ArrayList<>(countParams.size() + 2);
        rowParams.addAll(countParams);
        rowParams.add(safeSize + 1);
        rowParams.add(safePage * safeSize);
        String rowsSql = """
                SELECT directory.artist_key, directory.name, directory.initial, directory.gender,
                       directory.song_count, directory.reviewed, directory.artist_kind, directory.avatar_path,
                       directory.region
                FROM artist_directory_stats directory""" + whereSql
                + " ORDER BY CASE WHEN directory.artist_kind = 'UNATTRIBUTED' THEN 1 ELSE 0 END ASC,"
                + " directory.song_count DESC, directory.name ASC, directory.artist_key ASC LIMIT ? OFFSET ?";
        List<ArtistRow> rawRows = queryJdbc.query(
                rowsSql,
                (rs, index) -> new ArtistRow(
                        rs.getString("artist_key"), rs.getString("name"), rs.getString("initial"),
                        rs.getString("gender"), rs.getLong("song_count"), rs.getBoolean("reviewed"),
                        rs.getString("artist_kind"), rs.getString("avatar_path"), normalize(rs.getString("region"))),
                rowParams.toArray());
        boolean hasMore = rawRows.size() > safeSize;
        List<ArtistRow> rows = hasMore ? List.copyOf(rawRows.subList(0, safeSize)) : List.copyOf(rawRows);
        long totalCount;
        if (!hasMore && (safePage == 0 || !rows.isEmpty())) {
            totalCount = (long) safePage * safeSize + rows.size();
        } else {
            Long total = queryJdbc.queryForObject(
                    "SELECT COUNT(*) FROM artist_directory_stats directory" + whereSql,
                    Long.class, countParams.toArray());
            totalCount = total == null ? 0L : total;
        }
        ProjectionPage resultPage = new ProjectionPage(rows, totalCount);
        pageCache.put(cacheKey, new CachedProjectionPage(resultPage, System.nanoTime() + PAGE_CACHE_TTL_NANOS));
        return Optional.of(resultPage);
    }

    public Optional<List<String>> initials(String gender) {
        return initials(gender, "");
    }

    public Optional<List<String>> initials(String gender, String region) {
        if (!isReady()) return Optional.empty();
        String safeGender = normalize(gender);
        String safeRegion = normalize(region);
        long cacheRevision = projectionEpoch.get() ^ (CatalogCacheEpoch.current() << 20);
        String cacheKey = cacheRevision + "|" + safeGender + "|" + safeRegion;
        long now = System.nanoTime();
        CachedInitials cached = initialsCache.get(cacheKey);
        if (cached != null && now < cached.expiresAtNanos()) {
            return Optional.of(cached.initials());
        }

        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>(2);
        if (!safeGender.isEmpty()) {
            where.append(" WHERE directory.gender = ?");
            params.add(safeGender);
        }
        if (!safeRegion.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("directory.region = ?");
            params.add(safeRegion);
        }
        List<String> raw = queryJdbc.query(
                "SELECT DISTINCT directory.initial FROM artist_directory_stats directory"
                        + where + " ORDER BY directory.initial",
                (rs, index) -> rs.getString(1), params.toArray());
        List<String> normalized = normalizeInitialBuckets(raw);
        initialsCache.put(cacheKey, new CachedInitials(normalized, System.nanoTime() + INITIALS_CACHE_TTL_NANOS));
        return Optional.of(normalized);
    }

    static List<String> normalizeInitialBuckets(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        java.util.TreeSet<String> letters = new java.util.TreeSet<>();
        boolean hasOther = false;
        for (String item : raw) {
            if (item == null) continue;
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            char ch = normalized.charAt(0);
            if (ch >= 'A' && ch <= 'Z') {
                letters.add(String.valueOf(ch));
            } else {
                hasOther = true;
            }
        }
        List<String> result = new java.util.ArrayList<>(letters);
        if (hasOther) result.add("#");
        return List.copyOf(result);
    }

    public boolean hasRegionReference() {
        long now = System.nanoTime();
        Boolean cached = cachedRegionRef;
        if (cached != null && now < regionRefCachedUntilNanos) {
            return cached;
        }
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM iktv_artist_reference_snapshots WHERE status = 'ACTIVE')
                """, Boolean.class));
        cachedRegionRef = active;
        regionRefCachedUntilNanos = now + METADATA_CACHE_TTL_NANOS;
        return active;
    }

    private boolean isReady() {
        long now = System.nanoTime();
        if (now < readyCachedUntilNanos) {
            return true;
        }
        Boolean ready = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM artist_directory_projection_state WHERE projection_key = ?
                )
                """, Boolean.class, ACTIVE_PROJECTION);
        if (Boolean.TRUE.equals(ready)) {
            readyCachedUntilNanos = now + METADATA_CACHE_TTL_NANOS;
            return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public Optional<AdminProjectionPage> adminPage(String keyword, String gender, Boolean reviewed,
                                                   int page, int size) {
        if (!isReady()) {
            requestRefresh();
            return Optional.empty();
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String safeKeyword = normalize(keyword);
        String safeGender = normalize(gender);

        StringBuilder where = new StringBuilder();
        List<Object> filterArgs = new ArrayList<>();
        if (!safeGender.isEmpty()) {
            where.append(where.isEmpty() ? "WHERE " : " AND ").append("gender = ?");
            filterArgs.add(safeGender);
        }
        if (reviewed != null) {
            where.append(where.isEmpty() ? "WHERE " : " AND ").append("reviewed = ?");
            filterArgs.add(reviewed);
        }
        if (!safeKeyword.isEmpty()) {
            String lower = safeKeyword.toLowerCase(Locale.ROOT);
            String lowerLike = escapeLike(lower);
            boolean asciiOnly = isAscii(lower);
            where.append(where.isEmpty() ? "WHERE " : " AND ")
                    .append("(LOWER(name) LIKE '%' || ? || '%' ESCAPE '\\'");
            filterArgs.add(lowerLike);
            if (asciiOnly) {
                where.append(" OR LOWER(artist_key) LIKE '%' || ? || '%' ESCAPE '\\'")
                        .append(" OR LOWER(artist_init) LIKE ? || '%' ESCAPE '\\'")
                        .append(" OR LOWER(artist_py) LIKE ? || '%' ESCAPE '\\'");
                filterArgs.add(lowerLike);
                filterArgs.add(lowerLike);
                filterArgs.add(lowerLike);
            }
            where.append(")");
        }

        String whereSql = where.isEmpty() ? "" : " " + where;
        Long total = queryJdbc.queryForObject(
                "SELECT COUNT(*) FROM artist_directory_stats" + whereSql,
                Long.class,
                filterArgs.toArray());
        long totalCount = total == null ? 0L : total;
        if (totalCount == 0L) {
            if (where.isEmpty()) {
                requestRefresh();
            }
            return Optional.of(new AdminProjectionPage(List.of(), 0L));
        }

        List<Object> rowArgs = new ArrayList<>(filterArgs.size() + 2);
        rowArgs.addAll(filterArgs);
        rowArgs.add(safeSize);
        rowArgs.add(safePage * safeSize);
        List<AdminArtistRow> rows = queryJdbc.query(
                "SELECT artist_key, name, gender, song_count, reviewed, artist_kind, avatar_path "
                        + "FROM artist_directory_stats" + whereSql
                        + " ORDER BY song_count DESC, name ASC, artist_key ASC LIMIT ? OFFSET ?",
                (rs, index) -> new AdminArtistRow(
                        rs.getString("artist_key"),
                        rs.getString("name"),
                        rs.getString("gender"),
                        rs.getLong("song_count"),
                        rs.getBoolean("reviewed"),
                        rs.getString("artist_kind"),
                        rs.getString("avatar_path")),
                rowArgs.toArray());
        return Optional.of(new AdminProjectionPage(rows, totalCount));
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

    public record ArtistRow(String artistKey, String name, String initial, String gender,
                            long songCount, boolean reviewed, String artistKind, String avatarPath, String region) {
        public ArtistRow(String artistKey, String name, String initial, String gender,
                         long songCount, boolean reviewed, String artistKind, String avatarPath) {
            this(artistKey, name, initial, gender, songCount, reviewed, artistKind, avatarPath, "");
        }
    }

    public record ProjectionPage(List<ArtistRow> rows, long total) {}

    public record AdminArtistRow(String artistKey, String name, String gender, long songCount,
                                 boolean reviewed, String artistKind, String avatarPath) {}

    public record AdminProjectionPage(List<AdminArtistRow> rows, long total) {}
}
