package com.homektv.library;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
                artist_kind, avatar_path, updated_at)
            SELECT a.artist_key,
                   a.name,
                   CASE WHEN COALESCE(a.artist_init, '') = '' THEN '#'
                        ELSE upper(left(a.artist_init, 1)) END,
                   COALESCE(NULLIF(p.gender, '未知'), inferred.gender, '未知'),
                   a.song_count,
                   COALESCE(p.gender_status = 'MANUAL' AND NULLIF(p.gender, '未知') IS NOT NULL, false),
                   COALESCE(p.artist_kind,
                            CASE WHEN a.name IN ('群星', '多人', 'Various Artists') THEN 'VARIOUS'
                                 WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous')
                                      THEN 'UNATTRIBUTED'
                                 ELSE 'PERSON' END),
                   p.avatar_path,
                   now()
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            LEFT JOIN inferred_gender inferred ON inferred.artist_key = a.artist_key
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate refreshTransaction;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "artist-directory-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();

    public ArtistDirectoryProjectionService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtistDirectoryProjectionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.refreshTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
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
        if (refreshTransaction == null) {
            refreshInternal();
        } else {
            refreshTransaction.executeWithoutResult(status -> refreshInternal());
        }
    }

    private void refreshInternal() {
        jdbc.update("DELETE FROM artist_directory_stats");
        jdbc.update(REFRESH_SQL);
        jdbc.update("""
                INSERT INTO artist_directory_projection_state(projection_key, refreshed_at)
                VALUES (?, now())
                ON CONFLICT (projection_key) DO UPDATE SET refreshed_at = EXCLUDED.refreshed_at
                """, ACTIVE_PROJECTION);
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }
    public Optional<ProjectionPage> page(String gender, String initial, int page, int size) {
        if (!isReady()) return Optional.empty();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String safeGender = normalize(gender);
        String safeInitial = normalize(initial).toUpperCase(Locale.ROOT);
        if ("热门".equals(safeInitial)) safeInitial = "";
        String where = "WHERE (? = '' OR gender = ?) AND (? = '' OR initial = ?)";
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artist_directory_stats " + where,
                Long.class, safeGender, safeGender, safeInitial, safeInitial);
        List<ArtistRow> rows = jdbc.query("""
                SELECT artist_key, name, initial, gender, song_count, reviewed, artist_kind, avatar_path
                FROM artist_directory_stats
                """ + where + " ORDER BY song_count DESC, name ASC, artist_key ASC LIMIT ? OFFSET ?",
                (rs, index) -> new ArtistRow(
                        rs.getString("artist_key"), rs.getString("name"), rs.getString("initial"),
                        rs.getString("gender"), rs.getLong("song_count"), rs.getBoolean("reviewed"),
                        rs.getString("artist_kind"), rs.getString("avatar_path")),
                safeGender, safeGender, safeInitial, safeInitial, safeSize, safePage * safeSize);
        return Optional.of(new ProjectionPage(rows, total == null ? 0L : total));
    }

    public Optional<List<String>> initials(String gender) {
        if (!isReady()) return Optional.empty();
        String safeGender = normalize(gender);
        return Optional.of(jdbc.query("""
                SELECT DISTINCT initial
                FROM artist_directory_stats
                WHERE ? = '' OR gender = ?
                ORDER BY initial
                """, (rs, index) -> rs.getString(1), safeGender, safeGender));
    }

    private boolean isReady() {
        Boolean ready = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM artist_directory_projection_state WHERE projection_key = ?
                )
                """, Boolean.class, ACTIVE_PROJECTION);
        return Boolean.TRUE.equals(ready);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record ArtistRow(String artistKey, String name, String initial, String gender,
                            long songCount, boolean reviewed, String artistKind, String avatarPath) {}

    public record ProjectionPage(List<ArtistRow> rows, long total) {}
}
