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

    public void enqueueProfiles(Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        List<String> keys = ArtistCreditParser.normalize(names).stream()
                .filter(name -> !ArtistKindClassifier.isPlaceholder(name))
                .map(ArtistCreditParser::key)
                .filter(key -> !key.isBlank())
                .distinct().toList();
        enqueueKeys(keys, false);
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
        enqueuePendingProfiles();
    }

    /** Explicit administrator retry for profiles that still have no usable avatar. */
    public Map<String, Object> resetAndEnqueueMissingProfiles() {
        List<String> keys = worker.unresolvedKeys();
        com.homektv.musicsource.MusicSourceConfig config = configService.getConfig();
        if (config == null || !config.enabled() || config.providers() == null || config.providers().isEmpty()) {
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

    private void enqueueKeys(Collection<String> keys, boolean reopenTerminal) {
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
        if (config == null || !config.enabled() || config.providers() == null || config.providers().isEmpty()) return;
        List<JobSeed> jobs = boundedKeys.stream()
                .flatMap(key -> config.providers().stream().map(provider -> new JobSeed(key, provider.name())))
                .toList();
        if (jobs.isEmpty()) return;
        try {
            String conflictUpdate = reopenTerminal
                    ? """
                      ON CONFLICT (artist_key, provider) DO UPDATE SET
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
                        artist_key, provider, status, attempts, next_run_at, created_at, updated_at)
                    VALUES (?, ?, 'PENDING', 0, now(), now(), now())
                    """ + conflictUpdate, jobs, BATCH_SIZE, (statement, job) -> {
                        statement.setString(1, job.artistKey());
                        statement.setString(2, job.provider());
                    });
            boolean inserted = changed != null && changed.length > 0;
            if (inserted) worker.trigger();
        } catch (DataAccessException failure) {
            // During a rolling upgrade the migration may not be present yet;
            // the song scan must remain usable and retry on the next scan.
            log.warn("artist avatar jobs unavailable: {}", failure.getMessage());
        }
    }

    private record JobSeed(String artistKey, String provider) {}
}
