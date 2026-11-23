package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enqueues idempotent, application-owned artist avatar work. */
@Service
public class ArtistAvatarJobService {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarJobService.class);
    private static final int MAX_KEYS = 10_000;
    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final ArtistAvatarWorker worker;
    private final com.homektv.musicsource.MusicSourceConfigService configService;
    private final Executor executor;
    private final AtomicBoolean unresolvedRefreshRequested = new AtomicBoolean(false);
    private final AtomicBoolean unresolvedRefreshRunning = new AtomicBoolean(false);

    @org.springframework.beans.factory.annotation.Autowired
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
        enqueueKeys(worker.pendingKeys(), false);
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

    /** Reopens unresolved profiles when the administrator changes metadata providers. */
    public void enqueueUnresolvedProfiles() {
        enqueueKeys(worker.unresolvedKeys(), true);
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
        List<String> keys = worker.unresolvedKeys();
        com.homektv.musicsource.MusicSourceConfig config = configService.getConfig();
        boolean fallbackEnabled = configService.isAvatarFallbackEnabled();
        if (config == null || !config.enabled()
                || ((config.providers() == null || config.providers().isEmpty()) && !fallbackEnabled)) {
            return Map.of(
                    "queued", 0,
                    "alreadyPending", 0,
                    "nextWorkAt", Instant.now().toString(),
                    "reason", "音乐元数据服务未启用");
        }
        enqueueKeys(keys, true);
        return Map.of(
                "queued", keys.size(),
                "alreadyPending", 0,
                "nextWorkAt", Instant.now().toString());
    }

    /** Triggers high-priority avatar scraping for a single artist (local-first, then online). */
    public Map<String, Object> scrapeSingleArtist(String artistKey, LocalAvatarResolver localAvatarResolver) {
        String safeKey = artistKey == null ? "" : artistKey.trim();
        if (safeKey.isBlank()) {
            throw new com.homektv.web.ApiException("INVALID_ARTIST_KEY", "歌手 Key 不能为空");
        }
        var profile = worker.findProfile(safeKey)
                .orElseThrow(() -> new com.homektv.web.ApiException("ARTIST_NOT_FOUND", "歌手档案不存在"));
        if (ArtistKindClassifier.isPlaceholder(profile.displayName())) {
            throw new com.homektv.web.ApiException("INVALID_ARTIST_KIND", "占位歌手无需刮削头像");
        }
        if (worker.resetSingleArtistForScrape(safeKey)) {
            if (localAvatarResolver != null && localAvatarResolver.resolveAllCandidates() > 0) {
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
            throw new com.homektv.web.ApiException("MUSIC_SOURCE_UNAVAILABLE", "音乐元数据配置暂时不可用");
        }
        boolean fallbackEnabled = configService.isAvatarFallbackEnabled();
        if (config == null || !config.enabled()
                || ((config.providers() == null || config.providers().isEmpty()) && !fallbackEnabled)) {
            return Map.of("queued", 0, "resolvedLocally", false, "reason", "音乐元数据服务未启用");
        }
        enqueueKeys(List.of(safeKey), true, 100_000);
        return Map.of("queued", 1, "resolvedLocally", false, "status", "PENDING");
    }

    private void enqueueKeys(Collection<String> keys, boolean reopenTerminal) {
        enqueueKeys(keys, reopenTerminal, null);
    }

    private void enqueueKeys(Collection<String> keys, boolean reopenTerminal, Integer fixedPriority) {
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
        boolean fallbackEnabled = configService.isAvatarFallbackEnabled();
        boolean hasPrimary = config != null && config.providers() != null && !config.providers().isEmpty();
        if (config == null || !config.enabled() || (!hasPrimary && !fallbackEnabled)) return;

        Map<String, Integer> priorities = fixedPriority != null
                ? Map.of()
                : worker.songPriorities(boundedKeys);
        List<JobSeed> jobs = new java.util.ArrayList<>();
        for (String key : boundedKeys) {
            int priority = fixedPriority != null ? fixedPriority : priorities.getOrDefault(key, 0);
            if (hasPrimary) {
                for (var provider : config.providers()) {
                    jobs.add(new JobSeed(key, provider.name(), priority, 1));
                }
            }
            if (fallbackEnabled) {
                jobs.add(new JobSeed(key, "FALLBACK", priority, 0));
            }
        }
        if (jobs.isEmpty()) return;
        try {
            String conflictUpdate = reopenTerminal
                    ? """
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
                      """
                    : """
                      ON CONFLICT (artist_key, provider) DO NOTHING
                      """;
            int[][] changed = jdbc.batchUpdate("""
                    INSERT INTO artist_avatar_jobs(
                        artist_key, provider, priority, tier, status, attempts, next_run_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
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

    private record JobSeed(String artistKey, String provider, int priority, int tier) {}
}
