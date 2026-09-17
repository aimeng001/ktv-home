package com.homektv.library;

import com.homektv.musicsource.ArtistMetadataProvider;
import com.homektv.musicsource.ExternalArtist;
import com.homektv.musicsource.ExternalCoverService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.musicsource.MusicSourceException;
import com.homektv.musicsource.ProviderHttpException;
import com.homektv.musicsource.ProviderRateLimitedException;
import com.homektv.musicsource.ProviderRateStateUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single bounded worker for artist identity lookup and avatar caching. */
@Service
public class ArtistAvatarWorker {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarWorker.class);
    private static final int MAX_BATCH = 100;
    private static final int MAX_ENQUEUE_KEYS = 10_000;
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate jdbc;
    private final ArtistProfileService profiles;
    private final MusicSourceConfigService configService;
    private final ExternalCoverService coverService;
    private final AssetWriter assets;
    private final java.util.concurrent.Executor executor;
    private final Map<MusicProvider, ArtistMetadataProvider> providers = new EnumMap<>(MusicProvider.class);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ArtistAvatarWorker(JdbcTemplate jdbc, ArtistProfileService profiles,
                              MusicSourceConfigService configService,
                              ExternalCoverService coverService,
                              AssetWriter assets,
                              List<ArtistMetadataProvider> providers,
                              @Qualifier("artistAvatarExecutor") java.util.concurrent.Executor executor) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.configService = configService;
        this.coverService = coverService;
        this.assets = assets;
        this.executor = executor;
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
    }

    public List<String> pendingKeys() {
        return profiles.pendingAvatarKeys(MAX_ENQUEUE_KEYS);
    }

    /** Used only after an administrator changes providers to retry unresolved profiles. */
    public List<String> unresolvedKeys() {
        return profiles.unresolvedAvatarKeys(10_000);
    }

    public void trigger() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(this::process);
        } catch (RuntimeException failure) {
            running.set(false);
            log.debug("artist avatar worker could not be scheduled: {}", failure.getMessage());
        }
    }

    public void process() {
        boolean fullBatch = false;
        try {
            int processed = 0;
            while (processed < MAX_BATCH) {
                Job job = claimNext();
                if (job == null) return;
                process(job);
                processed++;
            }
            fullBatch = true;
        } finally {
            running.set(false);
            if (fullBatch) trigger();
        }
    }

    @Scheduled(fixedDelayString = "${app.artist-avatar.recovery-ms:60000}")
    void recoverStuckQueue() {
        trigger();
    }

    private void process(Job job) {
        ArtistProfileService.Profile profile;
        try {
            profile = profiles.find(job.artistKey()).orElse(null);
        } catch (DataAccessException failure) {
            log.debug("artist profile lookup unavailable for {}: {}", job.artistKey(), failure.getMessage());
            deferRetry(job, "歌手档案暂时不可用", Instant.now().plus(Duration.ofMinutes(10)));
            return;
        }
        if (profile == null) {
            finish(job, "SKIPPED", "歌手档案不存在");
            return;
        }
        if (ArtistKindClassifier.isPlaceholder(profile.displayName())) {
            profiles.markAvatarStatusIfClaimed(profile.artistKey(), "SKIPPED", "占位歌手不刮削头像",
                    job.id(), job.claimToken());
            finish(job, "SKIPPED", "占位歌手不刮削头像");
            return;
        }
        if (profile.avatarPath() != null && !profile.avatarPath().isBlank()) {
            if (assets.isReadableCache(profile.avatarPath())) {
                finish(job, "SKIPPED", "歌手已有缓存头像");
                return;
            }
            if (!profiles.markAvatarStatusIfClaimed(profile.artistKey(), "RETRY", "数据库记录的缓存头像不存在",
                    job.id(), job.claimToken())) return;
        }

        MusicSourceConfig config;
        try {
            config = configService.getConfig();
        } catch (RuntimeException failure) {
            log.debug("artist avatar configuration unavailable for {}: {}", profile.displayName(), failure.getMessage());
            deferRetry(job, "音乐元数据设置暂时不可用", Instant.now().plus(Duration.ofMinutes(10)));
            return;
        }
        if (config == null || !config.enabled() || config.providers() == null || config.providers().isEmpty()) {
            defer(job, "音乐元数据服务未启用", Instant.now().plus(Duration.ofMinutes(10)));
            return;
        }
        if (!config.providers().contains(job.provider())) {
            profiles.markAvatarStatusIfClaimed(profile.artistKey(), "SKIPPED", "该音乐平台当前未启用",
                    job.id(), job.claimToken());
            finish(job, "SKIPPED", "该音乐平台当前未启用");
            return;
        }
        ArtistMetadataProvider metadataProvider = providers.get(job.provider());
        if (metadataProvider == null) {
            profiles.markAvatarStatusIfClaimed(profile.artistKey(), "SKIPPED", "该平台暂不支持歌手头像搜索",
                    job.id(), job.claimToken());
            finish(job, "SKIPPED", "该音乐平台暂不支持歌手头像搜索");
            return;
        }
        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
        String downloadedPath = null;
        boolean avatarCommitted = false;
        try {
            ExternalArtist candidate = ArtistAvatarMatcher
                    .bestMatch(profile.displayName(), metadataProvider.search(profile.displayName(), 5, timeout))
                    .filter(match -> match.artist().avatarUrl() != null && !match.artist().avatarUrl().isBlank())
                    .map(ArtistAvatarMatcher.Match::artist)
                    .orElse(null);
            if (candidate == null) {
                profiles.markAvatarStatusIfClaimed(profile.artistKey(), "REVIEW", "未找到高置信度歌手头像",
                        job.id(), job.claimToken());
                finish(job, "REVIEW", "未找到高置信度歌手头像");
                return;
            }
            if (!renewLease(job)) return;
            if (!assets.hasArtistCoverCapacity(5L * 1024 * 1024)) {
                defer(job, "歌手头像缓存已达到容量上限，等待孤儿清理", Instant.now().plus(Duration.ofMinutes(10)));
                return;
            }
            downloadedPath = coverService.downloadArtistAvatar(job.provider(), candidate.avatarUrl(),
                    profile.artistKey() + ":" + job.claimToken(), timeout);
            avatarCommitted = profiles.markAvatarReadyIfClaimed(profile.artistKey(), job.provider().name(),
                    candidate.externalId(), downloadedPath, job.id(), job.claimToken());
            if (!avatarCommitted) return;
            finish(job, "COMPLETED", null);
        } catch (ProviderRateLimitedException failure) {
            log.debug("artist avatar provider {} is cooling down until {}", job.provider(), failure.retryAt());
            defer(job, "音乐平台处于冷却或限额状态", failure.retryAt());
        } catch (ProviderHttpException failure) {
            log.debug("artist avatar provider {} returned HTTP {} for {}", job.provider(),
                    failure.statusCode(), profile.displayName());
            deferRetry(job, "歌手头像平台暂时不可用", retryAt(job, failure));
        } catch (ProviderRateStateUnavailableException failure) {
            log.debug("artist avatar provider rate state unavailable for {}: {}",
                    profile.displayName(), failure.getMessage());
            defer(job, "音乐平台限速状态暂时不可用", Instant.now().plus(Duration.ofMinutes(10)));
        } catch (MusicSourceException failure) {
            log.debug("artist avatar provider {} failed for {}: {}", job.provider(),
                    profile.displayName(), failure.getMessage());
            deferRetry(job, "歌手头像平台暂时不可用", retryAt(job, null));
        } catch (RuntimeException failure) {
            log.debug("artist avatar provider {} failed for {}: {}", job.provider(), profile.displayName(), failure.getMessage());
            deferRetry(job, "歌手头像服务暂时不可用", retryAt(job, null));
        } finally {
            if (downloadedPath != null && !avatarCommitted) {
                assets.deleteReadableCache(downloadedPath);
            }
        }
    }

    private Job claimNext() {
        try {
            String claimToken = java.util.UUID.randomUUID().toString();
            List<Job> claimed = jdbc.query("""
                    WITH candidate AS (
                        SELECT job.id
                        FROM artist_avatar_jobs job
                        WHERE (
                                (job.status = 'PENDING' AND job.next_run_at <= now())
                                OR (job.status = 'PROCESSING'
                                    AND COALESCE(job.lease_until, job.updated_at) <= now())
                              )
                          AND NOT EXISTS (
                                SELECT 1
                                FROM artist_avatar_jobs active
                                WHERE active.artist_key = job.artist_key
                                  AND active.id <> job.id
                                  AND active.status = 'PROCESSING'
                                  AND active.lease_until > now()
                              )
                        ORDER BY job.id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE artist_avatar_jobs job
                    SET status='PROCESSING',
                        attempts=job.attempts,
                        next_run_at=now(),
                        last_error=NULL,
                        lease_until=now() + interval '10 minutes',
                        claim_token=?::uuid,
                        updated_at=now()
                    FROM candidate
                    WHERE job.id = candidate.id
                    RETURNING job.id, job.artist_key, job.provider, job.attempts,
                              job.claim_token::text AS claim_token
                    """, (rs, index) -> new Job(rs.getLong("id"), rs.getString("artist_key"),
                            MusicProvider.parse(rs.getString("provider")), rs.getInt("attempts"),
                            rs.getString("claim_token")),
                    claimToken);
            return claimed.stream().findFirst().orElse(null);
        } catch (DataAccessException failure) {
            log.debug("artist avatar queue unavailable: {}", failure.getMessage());
            return null;
        }
    }

    private void finish(Job job, String status, String error) {
        jdbc.update("""
                UPDATE artist_avatar_jobs
                SET status=?, last_error=?, lease_until=NULL, claim_token=NULL, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                """, status, clean(error), job.id(), job.claimToken());
    }

    private boolean renewLease(Job job) {
        try {
            return jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET lease_until=now() + interval '10 minutes', updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                    """, job.id(), job.claimToken()) == 1;
        } catch (DataAccessException failure) {
            return false;
        }
    }

    private void defer(Job job, String reason, Instant retryAt) {
        jdbc.update("""
                UPDATE artist_avatar_jobs
                SET status='PENDING', next_run_at=?,
                    lease_until=NULL, claim_token=NULL, last_error=?, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                """, OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC), reason, job.id(), job.claimToken());
    }

    private void deferRetry(Job job, String reason, Instant retryAt) {
        jdbc.update("""
                UPDATE artist_avatar_jobs
                SET status=CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    attempts=attempts+1,
                    next_run_at=CASE WHEN attempts + 1 >= ? THEN now() ELSE ? END,
                    lease_until=NULL, claim_token=NULL, last_error=?, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                """, MAX_ATTEMPTS, MAX_ATTEMPTS,
                OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC), reason, job.id(), job.claimToken());
    }

    private Instant retryAt(Job job, ProviderHttpException failure) {
        Instant now = Instant.now();
        if (failure != null && failure.statusCode() == 429 && failure.retryAfter() != null) {
            return failure.retryAfter().isAfter(now) ? failure.retryAfter() : now.plusSeconds(3600);
        }
        if (failure != null && failure.statusCode() == 403) return now.plus(Duration.ofHours(24));
        return now.plus(Duration.ofSeconds(switch (Math.min(job.attempts + 1, 4)) {
            case 1 -> 15 * 60L;
            case 2 -> 60 * 60L;
            case 3 -> 6 * 60 * 60L;
            default -> 24 * 60 * 60L;
        }));
    }

    private record Job(long id, String artistKey, MusicProvider provider, int attempts, String claimToken) {}

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String compact = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return compact.length() <= 1000 ? compact : compact.substring(0, 1000);
    }
}
