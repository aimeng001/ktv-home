package com.homektv.library;

import com.homektv.musicsource.ArtistMetadataProvider;
import com.homektv.musicsource.ExternalArtist;
import com.homektv.musicsource.ExternalCoverService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
        return profiles.pendingAvatarKeys(MAX_BATCH);
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

    /** Retries deferred provider failures without requiring another library scan. */
    @Scheduled(initialDelay = 30_000L, fixedDelay = 60_000L)
    public void scheduledTrigger() { trigger(); }

    public void process() {
        try {
            int processed = 0;
            while (processed < MAX_BATCH) {
                Job job = claimNext();
                if (job == null) return;
                process(job);
                processed++;
            }
        } finally {
            running.set(false);
        }
    }

    private void process(Job job) {
        ArtistProfileService.Profile profile;
        try {
            profile = profiles.find(job.artistKey()).orElse(null);
        } catch (DataAccessException failure) {
            log.debug("artist profile lookup unavailable for {}: {}", job.artistKey(), failure.getMessage());
            defer(job, "歌手档案暂时不可用");
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
            defer(job, "音乐元数据设置暂时不可用");
            return;
        }
        if (config == null || !config.enabled() || config.providers() == null || config.providers().isEmpty()) {
            defer(job, "音乐元数据服务未启用");
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
            String path = coverService.downloadArtistAvatar(job.provider(), candidate.avatarUrl(),
                    profile.artistKey() + ":" + job.claimToken(), timeout);
            if (!profiles.markAvatarReadyIfClaimed(profile.artistKey(), job.provider().name(),
                    candidate.externalId(), path, job.id(), job.claimToken())) {
                assets.deleteReadableCache(path);
                return;
            }
            finish(job, "COMPLETED", null);
        } catch (RuntimeException failure) {
            log.debug("artist avatar provider {} failed for {}: {}", job.provider(), profile.displayName(), failure.getMessage());
            defer(job, "歌手头像服务暂时不可用");
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
                        attempts=job.attempts+1,
                        next_run_at=now(),
                        last_error=NULL,
                        lease_until=now() + interval '10 minutes',
                        claim_token=?::uuid,
                        updated_at=now()
                    FROM candidate
                    WHERE job.id = candidate.id
                    RETURNING job.id, job.artist_key, job.provider, job.claim_token::text AS claim_token
                    """, (rs, index) -> new Job(rs.getLong("id"), rs.getString("artist_key"),
                            MusicProvider.parse(rs.getString("provider")), rs.getString("claim_token")),
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

    private void defer(Job job, String reason) {
        jdbc.update("""
                UPDATE artist_avatar_jobs
                SET status='PENDING', next_run_at=now() + interval '10 minutes',
                    lease_until=NULL, claim_token=NULL, last_error=?, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                """, reason, job.id(), job.claimToken());
    }

    private record Job(long id, String artistKey, MusicProvider provider, String claimToken) {}

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String compact = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return compact.length() <= 1000 ? compact : compact.substring(0, 1000);
    }
}
