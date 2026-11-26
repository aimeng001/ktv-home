package com.homektv.library;
import com.homektv.common.ArtistKindClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enqueues idempotent, single-row-per-artist avatar work. */
@Service
public class ArtistAvatarJobService {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarJobService.class);
    static final String AUTO_PROVIDER = "AUTO";
    private static final int MAX_KEYS = 10_000;
    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final ArtistAvatarWorker worker;
    private final com.homektv.musicsource.MusicSourceConfigService configService;
    private final Executor executor;
    private final AtomicBoolean unresolvedRefreshRequested = new AtomicBoolean(false);
    private final AtomicBoolean unresolvedRefreshRunning = new AtomicBoolean(false);

    @Autowired
    public ArtistAvatarJobService(JdbcTemplate jdbc, ArtistAvatarWorker worker,
                                  com.homektv.musicsource.MusicSourceConfigService configService,
                                  @Qualifier("artistAvatarExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.worker = worker;
        this.configService = configService;
        this.executor = executor;
    }

    public ArtistAvatarJobService(JdbcTemplate jdbc, ArtistAvatarWorker worker,
                                  com.homektv.musicsource.MusicSourceConfigService configService) {
        this(jdbc, worker, configService, Runnable::run);
    }

    public void enqueuePendingProfiles() {
        enqueueKeys(worker.pendingKeys(), false, null, false);
    }

    /** Enqueues work after the caller's transaction has committed. */
    public void enqueuePendingProfilesAfterCommit() {
        Runnable action = this::enqueuePendingProfiles;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** Reopens unresolved profiles when the administrator changes metadata providers (preserving tried_providers). */
    public void enqueueUnresolvedProfiles() {
        enqueueKeys(worker.unresolvedKeys(), true, null, false);
    }

    /** Configuration writes return promptly while the retry queue is rebuilt in the background. */
    public void enqueueUnresolvedProfilesAsync() {
        unresolvedRefreshRequested.set(true);
        submitUnresolvedRefreshIfPossible();
    }

    private void submitUnresolvedRefreshIfPossible() {
        if (!unresolvedRefreshRequested.get()
                || !unresolvedRefreshRunning.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    if (unresolvedRefreshRequested.getAndSet(false)) enqueueUnresolvedProfiles();
                } finally {
                    unresolvedRefreshRunning.set(false);
                    if (unresolvedRefreshRequested.get()) submitUnresolvedRefreshIfPossible();
                }
            });
        } catch (RejectedExecutionException failure) {
            unresolvedRefreshRunning.set(false);
            log.debug("unresolved artist avatar refresh queued for retry: {}", failure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.artist-avatar.enqueue-retry-ms:60000}")
    void retryRejectedUnresolvedRefresh() {
        submitUnresolvedRefreshIfPossible();
    }

    /** Reconciles profiles beyond the bounded first page after a large scan. */
    @Scheduled(fixedDelayString = "${app.artist-avatar.pending-reconcile-ms:60000}")
    void retryPendingProfileEnqueue() {
        try {
            executor.execute(this::enqueuePendingProfiles);
        } catch (RejectedExecutionException failure) {
            log.debug("pending artist avatar profile enqueue queued for retry: {}", failure.getMessage());
        }
    }

    /** Explicit administrator retry for profiles that still have no usable avatar. */
    public Map<String, Object> resetAndEnqueueMissingProfiles() {
        worker.requeueMissingCachedAvatars();
        List<String> keys = worker.unresolvedKeys();
        com.homektv.musicsource.MusicSourceConfig config = configService.getConfig();
        if (!hasUsableAvatarSources(config)) {
            return Map.of(
                    "queued", 0,
                    "alreadyPending", 0,
                    "nextWorkAt", Instant.now().toString(),
                    "reason", "音乐元数据服务未启用");
        }
        enqueueKeys(keys, true, null, true);
        return Map.of(
                "queued", keys.size(),
                "alreadyPending", 0,
                "nextWorkAt", Instant.now().toString());
    }

    /** Triggers high-priority avatar scraping for a single artist (local-first, then online). */
    public Map<String, Object> scrapeSingleArtist(String artistKey, LocalAvatarResolver localAvatarResolver) {
        String safeKey = artistKey == null ? "" : artistKey.trim();
        if (safeKey.isBlank()) {
            throw new com.homektv.common.ApiException("INVALID_ARTIST_KEY", "歌手 Key 不能为空");
        }
        var profile = worker.findProfile(safeKey)
                .orElseThrow(() -> new com.homektv.common.ApiException("ARTIST_NOT_FOUND", "歌手档案不存在"));
        if (ArtistKindClassifier.isPlaceholder(profile.displayName())) {
            throw new com.homektv.common.ApiException("INVALID_ARTIST_KIND", "占位歌手无需刮削头像");
        }
        if (worker.resetSingleArtistForScrape(safeKey)) {
            if (localAvatarResolver != null && localAvatarResolver.resolveSingleArtist(profile)) {
                var refreshed = worker.findProfile(safeKey).orElse(profile);
                if (refreshed.avatarPath() != null && !refreshed.avatarPath().isBlank()) {
                    return Map.of("queued", 0, "resolvedLocally", true, "status", "READY");
                }
            }
        }
        com.homektv.musicsource.MusicSourceConfig config;
        try {
            config = configService.getConfig();
        } catch (DataAccessException failure) {
            throw new com.homektv.common.ApiException("MUSIC_SOURCE_UNAVAILABLE", "音乐元数据配置暂时不可用");
        }
        if (!hasUsableAvatarSources(config)) {
            return Map.of("queued", 0, "resolvedLocally", false, "reason", "音乐元数据服务未启用");
        }
        enqueueKeys(List.of(safeKey), true, 100_000, true);
        return Map.of("queued", 1, "resolvedLocally", false, "status", "PENDING");
    }

    private boolean hasUsableAvatarSources(com.homektv.musicsource.MusicSourceConfig config) {
        if (config == null || !config.enabled()) return false;
        boolean hasPrimary = config.providers() != null && !config.providers().isEmpty();
        boolean hasFallback = configService.isAvatarFallbackEnabled()
                || (config.fallbackProviders() != null && !config.fallbackProviders().isEmpty());
        return hasPrimary || hasFallback;
    }

    private void enqueueKeys(Collection<String> keys, boolean reopenTerminal,
                             Integer fixedPriority, boolean clearTriedProviders) {
        if (keys == null || keys.isEmpty()) return;
        List<String> boundedKeys = keys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .distinct()
                .limit(MAX_KEYS)
                .toList();
        if (boundedKeys.isEmpty()) return;
        com.homektv.musicsource.MusicSourceConfig config;
        try {
            config = configService.getConfig();
        } catch (DataAccessException failure) {
            log.debug("artist avatar configuration unavailable: {}", failure.getMessage());
            return;
        }
        if (!hasUsableAvatarSources(config)) return;

        Map<String, Integer> priorities = fixedPriority != null
                ? Map.of()
                : worker.songPriorities(boundedKeys);
        List<JobSeed> jobs = new java.util.ArrayList<>(boundedKeys.size());
        for (String key : boundedKeys) {
            int priority = fixedPriority != null ? fixedPriority : priorities.getOrDefault(key, 0);
            jobs.add(new JobSeed(key, AUTO_PROVIDER, priority, 1));
        }
        if (jobs.isEmpty()) return;
        try {
            String conflictUpdate;
            if (!reopenTerminal) {
                conflictUpdate = """
                        ON CONFLICT (artist_key, provider) DO NOTHING
                        """;
            } else if (clearTriedProviders) {
                conflictUpdate = """
                        ON CONFLICT (artist_key, provider) DO UPDATE SET
                            priority = GREATEST(artist_avatar_jobs.priority, EXCLUDED.priority),
                            tier = EXCLUDED.tier,
                            status = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW') THEN 'PENDING'
                                          ELSE artist_avatar_jobs.status END,
                            attempts = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW') THEN 0
                                            ELSE artist_avatar_jobs.attempts END,
                            tried_providers = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW')
                                                   THEN '{}'::text[] ELSE artist_avatar_jobs.tried_providers END,
                            next_run_at = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW')
                                              THEN now() ELSE artist_avatar_jobs.next_run_at END,
                            last_error = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW')
                                              THEN NULL ELSE artist_avatar_jobs.last_error END,
                            updated_at = now()
                        """;
            } else {
                conflictUpdate = """
                        ON CONFLICT (artist_key, provider) DO UPDATE SET
                            priority = GREATEST(artist_avatar_jobs.priority, EXCLUDED.priority),
                            tier = EXCLUDED.tier,
                            status = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW') THEN 'PENDING'
                                          ELSE artist_avatar_jobs.status END,
                            attempts = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW') THEN 0
                                            ELSE artist_avatar_jobs.attempts END,
                            next_run_at = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW')
                                              THEN now() ELSE artist_avatar_jobs.next_run_at END,
                            updated_at = now()
                        """;
            }
            int[][] changed = jdbc.batchUpdate("""
                    INSERT INTO artist_avatar_jobs(
                        artist_key, provider, priority, tier, status, attempts, tried_providers, next_run_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, '{}', now(), now(), now())
                    """ + conflictUpdate, jobs, BATCH_SIZE, (statement, job) -> {
                        statement.setString(1, job.artistKey());
                        statement.setString(2, job.provider());
                        statement.setInt(3, job.priority());
                        statement.setInt(4, job.tier());
                    });
            boolean inserted = changed != null && changed.length > 0;
            if (inserted) worker.trigger();
        } catch (DataAccessException failure) {
            // During a rolling upgrade the migration may not be present yet;
            // the song scan must remain usable and retry on the next scan.
            log.warn("artist avatar jobs unavailable: {}", failure.getMessage());
        }
    }

    public com.homektv.common.BackgroundJobSummary jobStatus() {
        try {
            Map<String, Long> counts = jdbc.query("""
                    SELECT status, COUNT(*) AS cnt FROM artist_avatar_jobs GROUP BY status
                    """, rs -> {
                Map<String, Long> map = new java.util.LinkedHashMap<>();
                while (rs.next()) map.put(rs.getString("status"), rs.getLong("cnt"));
                return map;
            });
            if (counts == null || counts.isEmpty()) {
                return new com.homektv.common.BackgroundJobSummary(
                        "artist-avatar", "歌手头像刮削", "IDLE", false, 0, 0, 0, 0, false, false, Map.of());
            }
            long pending = counts.getOrDefault("PENDING", 0L) + counts.getOrDefault("PROCESSING", 0L);
            long failed = counts.getOrDefault("FAILED", 0L);
            long completed = counts.getOrDefault("DONE", 0L) + counts.getOrDefault("SKIPPED", 0L)
                    + counts.getOrDefault("COMPLETED", 0L);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            boolean running = counts.getOrDefault("PROCESSING", 0L) > 0 || pending > 0;
            return new com.homektv.common.BackgroundJobSummary(
                    "artist-avatar", "歌手头像刮削", running ? "RUNNING" : "IDLE",
                    running, total, completed, failed, pending, false, false, Map.of());
        } catch (Exception ex) {
            return new com.homektv.common.BackgroundJobSummary(
                    "artist-avatar", "歌手头像刮削", "UNKNOWN", false, 0, 0, 0, 0, false, false, Map.of());
        }
    }

    private record JobSeed(String artistKey, String provider, int priority, int tier) {}
}
