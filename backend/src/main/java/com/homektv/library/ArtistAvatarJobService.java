package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Enqueues idempotent, application-owned artist avatar work. */
@Service
public class ArtistAvatarJobService {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarJobService.class);
    private static final int MAX_KEYS = 10_000;
    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final ArtistAvatarWorker worker;
    private final com.homektv.musicsource.MusicSourceConfigService configService;

    public ArtistAvatarJobService(JdbcTemplate jdbc, ArtistAvatarWorker worker,
                                  com.homektv.musicsource.MusicSourceConfigService configService) {
        this.jdbc = jdbc;
        this.worker = worker;
        this.configService = configService;
    }

    public void enqueueProfiles(Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        List<String> keys = ArtistCreditParser.normalize(names).stream()
                .filter(name -> !ArtistKindClassifier.isPlaceholder(name))
                .map(ArtistCreditParser::key)
                .filter(key -> !key.isBlank())
                .distinct().toList();
        enqueueKeys(keys);
    }

    public void enqueuePendingProfiles() {
        enqueueKeys(worker.pendingKeys());
    }

    /** Reopens unresolved profiles when the administrator changes metadata providers. */
    public void enqueueUnresolvedProfiles() {
        enqueueKeys(worker.unresolvedKeys());
    }

    /** Configuration writes return promptly while the retry queue is rebuilt in the background. */
    @Async("artistProfileExecutor")
    public void enqueueUnresolvedProfilesAsync() {
        enqueueUnresolvedProfiles();
    }

    private void enqueueKeys(Collection<String> keys) {
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
            int[][] changed = jdbc.batchUpdate("""
                    INSERT INTO artist_avatar_jobs(
                        artist_key, provider, status, attempts, next_run_at, created_at, updated_at)
                    VALUES (?, ?, 'PENDING', 0, now(), now(), now())
                    ON CONFLICT (artist_key, provider) DO UPDATE SET
                        status = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW') THEN 'PENDING'
                                      ELSE artist_avatar_jobs.status END,
                        next_run_at = CASE WHEN artist_avatar_jobs.status IN ('FAILED', 'SKIPPED', 'REVIEW')
                                          THEN now() ELSE artist_avatar_jobs.next_run_at END,
                        updated_at = now()
                    """, jobs, BATCH_SIZE, (statement, job) -> {
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
