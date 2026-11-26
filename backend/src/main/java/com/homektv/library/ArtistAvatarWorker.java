package com.homektv.library;
import com.homektv.common.ArtistKindClassifier;

import com.homektv.musicsource.ArtistAvatarFallbackChain;
import com.homektv.musicsource.ArtistMetadataProvider;
import com.homektv.musicsource.ExternalArtist;
import com.homektv.musicsource.ExternalCoverService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.musicsource.MusicSourceException;
import com.homektv.musicsource.ProviderCallGuard;
import com.homektv.musicsource.ProviderHttpException;
import com.homektv.musicsource.ProviderRateLimitedException;
import com.homektv.musicsource.ProviderRateStateUnavailableException;
import com.homektv.musicsource.ProviderRequestRateLimiter;
import com.homektv.common.ApiException;
import com.homektv.resource.PlaybackPressureMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conservative multi-worker avatar scraper:
 * - Processes 1 AUTO job row per artist across up to 3 cross-platform parallel threads.
 * - Routes each job to the next untried, unblocked primary provider with earliest slot availability,
 *   then executes the web fallback chain only after all enabled primary platforms miss.
 * - Automatically throttles down to 1 worker during active KTV playback.
 */
@Service
public class ArtistAvatarWorker {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarWorker.class);
    private static final int MAX_WORKERS = 3;
    private static final int MAX_BATCH = 100;
    private static final int MAX_ENQUEUE_KEYS = 10_000;
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate jdbc;
    private final ArtistProfileService profiles;
    private final MusicSourceConfigService configService;
    private final ExternalCoverService coverService;
    private final AssetWriter assets;
    private final Executor executor;
    private final Map<MusicProvider, ArtistMetadataProvider> providers = new EnumMap<>(MusicProvider.class);
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final ConcurrentHashMap<Long, String> activeClaims = new ConcurrentHashMap<>();
    private final Set<Thread> activeWorkerThreads = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown = false;
    private ArtistAvatarFallbackChain fallbackChain;
    private volatile PlaybackPressureMonitor playbackPressureMonitor;
    private volatile ProviderRequestRateLimiter rateLimiter;
    private volatile ProviderCallGuard callGuard;

    @Autowired(required = false)
    void setPlaybackPressureMonitor(PlaybackPressureMonitor playbackPressureMonitor) {
        this.playbackPressureMonitor = playbackPressureMonitor;
    }

    @Autowired(required = false)
    void setRateLimiter(ProviderRequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Autowired(required = false)
    void setCallGuard(ProviderCallGuard callGuard) {
        this.callGuard = callGuard;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        shuttingDown = true;
        activeWorkerThreads.forEach(Thread::interrupt);
        for (var entry : activeClaims.entrySet()) {
            try {
                jdbc.update("""
                        UPDATE artist_avatar_jobs
                        SET status='PENDING', lease_until=NULL, claim_token=NULL, updated_at=now()
                        WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                        """, entry.getKey(), entry.getValue());
            } catch (DataAccessException ignored) {}
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedJobsOnStartup() {
        if (shuttingDown) return;
        try {
            int released = jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET status='PENDING',
                        next_run_at=LEAST(next_run_at, now()),
                        lease_until=NULL,
                        claim_token=NULL,
                        updated_at=now()
                    WHERE status='PROCESSING'
                    """);
            if (released > 0) {
                log.info("容器启动恢复：已立即释放 {} 条中断的歌手头像刮削租约并续跑", released);
            }
            if (!shuttingDown) {
                try {
                    Boolean hasLegacyRows = jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM artist_avatar_jobs WHERE provider <> 'AUTO')",
                            Boolean.class);
                    if (Boolean.TRUE.equals(hasLegacyRows)) {
                        jdbc.update("DELETE FROM artist_avatar_jobs WHERE provider <> 'AUTO'");
                    }
                } catch (DataAccessException ignored) {}
            }
            trigger();
        } catch (DataAccessException failure) {
            log.debug("artist avatar startup lease recovery skipped: {}", failure.getMessage());
        }
    }

    public ArtistAvatarWorker(JdbcTemplate jdbc, ArtistProfileService profiles,
                              MusicSourceConfigService configService,
                              ExternalCoverService coverService,
                              AssetWriter assets,
                              List<ArtistMetadataProvider> providers,
                              @Qualifier("artistAvatarExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.configService = configService;
        this.coverService = coverService;
        this.assets = assets;
        this.executor = executor;
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
    }

    @Autowired(required = false)
    void setFallbackChain(ArtistAvatarFallbackChain fallbackChain) {
        this.fallbackChain = fallbackChain;
    }

    public List<String> pendingKeys() {
        return profiles.pendingAvatarKeys(MAX_ENQUEUE_KEYS);
    }

    /** Used only after an administrator changes providers to retry unresolved profiles. */
    public List<String> unresolvedKeys() {
        return profiles.unresolvedAvatarKeys(10_000);
    }

    public Map<String, Integer> songPriorities(Collection<String> keys) {
        return profiles.artistSongPriorities(keys);
    }

    public java.util.Optional<ArtistProfileService.Profile> findProfile(String artistKey) {
        return profiles.find(artistKey);
    }

    public boolean resetSingleArtistForScrape(String artistKey) {
        return profiles.resetSingleArtistForScrape(artistKey);
    }

    public int requeueMissingCachedAvatars() {
        return profiles.requeueMissingCachedAvatars(assets);
    }

    public void trigger() {
        if (shuttingDown) return;
        int target = targetConcurrency();
        for (int attempt = 0; attempt < target; attempt++) {
            if (shuttingDown) break;
            int current = activeWorkers.get();
            if (current >= target) break;
            if (!activeWorkers.compareAndSet(current, current + 1)) continue;
            AtomicBoolean queueDrained = new AtomicBoolean(false);
            try {
                executor.execute(() -> runWorkerSlot(queueDrained));
            } catch (RuntimeException failure) {
                activeWorkers.decrementAndGet();
                log.debug("artist avatar worker could not be scheduled: {}", failure.getMessage());
                break;
            }
            // If an inline test executor ran synchronously and drained the queue, stop launching redundant empty polls.
            if (queueDrained.get()) break;
        }
    }

    public void process() {
        activeWorkers.incrementAndGet();
        runWorkerSlot(new AtomicBoolean());
    }

    private void runWorkerSlot(AtomicBoolean queueDrained) {
        boolean fullBatch = false;
        Thread currentThread = Thread.currentThread();
        boolean trackInterrupt = !shuttingDown;
        if (trackInterrupt) activeWorkerThreads.add(currentThread);
        try {
            boolean busy = isPlaybackBusy();
            int batchLimit = busy ? 1 : MAX_BATCH;
            int processed = 0;
            while (processed < batchLimit && !shuttingDown && !Thread.currentThread().isInterrupted()) {
                Job job = claimNext();
                if (job == null) {
                    queueDrained.set(true);
                    return;
                }
                activeClaims.put(job.id(), job.claimToken());
                try {
                    process(job);
                } finally {
                    activeClaims.remove(job.id(), job.claimToken());
                }
                processed++;
                if (!busy && isPlaybackBusy()) {
                    busy = true;
                    break;
                }
            }
            fullBatch = !shuttingDown && !busy && processed >= MAX_BATCH;
        } finally {
            activeWorkerThreads.remove(currentThread);
            activeWorkers.decrementAndGet();
            if (fullBatch && !shuttingDown && !Thread.currentThread().isInterrupted()) trigger();
        }
    }

    @Scheduled(fixedDelayString = "${app.artist-avatar.recovery-ms:60000}")
    void recoverStuckQueue() {
        trigger();
    }

    private boolean isPlaybackBusy() {
        return playbackPressureMonitor != null && playbackPressureMonitor.isPlaybackBusy();
    }

    private int targetConcurrency() {
        return isPlaybackBusy() ? 1 : MAX_WORKERS;
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
        if (config == null || !config.enabled()) {
            defer(job, "音乐元数据服务未启用", Instant.now().plus(Duration.ofMinutes(10)));
            return;
        }
        Duration timeout = Duration.ofSeconds(Math.max(4, config.timeoutSeconds()));

        if ("AUTO".equalsIgnoreCase(job.providerCode())) {
            processAuto(job, profile, config, timeout);
            return;
        }
        if ("FALLBACK".equalsIgnoreCase(job.providerCode())) {
            processFallback(job, profile, timeout, false);
            return;
        }
        processLegacyProvider(job, profile, config, timeout);
    }

    private void processAuto(Job job, ArtistProfileService.Profile profile, MusicSourceConfig config, Duration timeout) {
        Set<String> tried = new LinkedHashSet<>(job.triedProviders());
        List<MusicProvider> enabledOrdered = orderedEnabledProviders(config);
        Set<MusicProvider> rateLimitedInRun = EnumSet.noneOf(MusicProvider.class);
        Set<MusicProvider> transientFailedInRun = EnumSet.noneOf(MusicProvider.class);
        Instant earliestRetryAt = null;
        String lastError = null;
        boolean triedChanged = false;

        while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
            List<MusicProvider> candidates = new ArrayList<>();
            for (MusicProvider candidate : enabledOrdered) {
                if (tried.contains(candidate.name())
                        || rateLimitedInRun.contains(candidate)
                        || transientFailedInRun.contains(candidate)) {
                    continue;
                }
                if (providers.containsKey(candidate)) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) break;

            SelectionResult selection = selectBestAvailableProvider(candidates);
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                defer(job, "服务关闭中，任务已释放待续跑", Instant.now());
                return;
            }
            if (selection.selected() == null) {
                if (selection.earliestBlockedUntil() != null) {
                    earliestRetryAt = earliest(earliestRetryAt, selection.earliestBlockedUntil());
                    rateLimitedInRun.addAll(candidates);
                }
                break;
            }
            MusicProvider provider = selection.selected();
            ArtistMetadataProvider metadataProvider = providers.get(provider);
            if (!renewLease(job)) return;

            String downloadedPath = null;
            boolean avatarCommitted = false;
            try {
                ExternalArtist match = ArtistAvatarMatcher
                        .bestMatch(profile.displayName(), metadataProvider.search(profile.displayName(), 5, timeout))
                        .filter(found -> found.artist().avatarUrl() != null && !found.artist().avatarUrl().isBlank())
                        .map(ArtistAvatarMatcher.Match::artist)
                        .orElse(null);
                if (match == null) {
                    lastError = "未找到高置信度歌手头像";
                    tried.add(provider.name());
                    triedChanged = true;
                    continue;
                }
                if (!renewLease(job)) return;
                if (!assets.hasArtistCoverCapacity(5L * 1024 * 1024)) {
                    if (triedChanged) persistTriedProviders(job, tried);
                    defer(job, "歌手头像缓存已达到容量上限，等待孤儿清理", Instant.now().plus(Duration.ofMinutes(10)));
                    return;
                }
                downloadedPath = coverService.downloadArtistAvatar(provider, match.avatarUrl(),
                        profile.artistKey() + ":" + job.claimToken(), timeout);
                avatarCommitted = profiles.markAvatarReadyIfClaimed(profile.artistKey(), provider.name(),
                        match.externalId(), downloadedPath, job.id(), job.claimToken());
                if (!avatarCommitted) return;
                finish(job, "COMPLETED", null);
                return;
            } catch (ApiException failure) {
                String code = failure.getCode();
                if ("AVATAR_IMAGE_REJECTED".equals(code) || "EXTERNAL_COVER_PLACEHOLDER".equals(code)
                        || "EXTERNAL_COVER_INVALID".equals(code)) {
                    lastError = failure.getMessage();
                    tried.add(provider.name());
                    triedChanged = true;
                    continue;
                }
                transientFailedInRun.add(provider);
                lastError = failure.getMessage();
                earliestRetryAt = earliest(earliestRetryAt, retryAt(job, null));
            } catch (ProviderRateLimitedException failure) {
                rateLimitedInRun.add(provider);
                lastError = "音乐平台处于冷却或限额状态";
                earliestRetryAt = earliest(earliestRetryAt, failure.retryAt());
            } catch (ProviderRateStateUnavailableException failure) {
                rateLimitedInRun.add(provider);
                lastError = "音乐平台限速状态暂时不可用";
                earliestRetryAt = earliest(earliestRetryAt, Instant.now().plus(Duration.ofMinutes(10)));
            } catch (ProviderHttpException failure) {
                transientFailedInRun.add(provider);
                lastError = "歌手头像平台暂时不可用";
                earliestRetryAt = earliest(earliestRetryAt, retryAt(job, failure));
            } catch (MusicSourceException failure) {
                transientFailedInRun.add(provider);
                lastError = "歌手头像平台暂时不可用";
                earliestRetryAt = earliest(earliestRetryAt, retryAt(job, null));
            } catch (RuntimeException failure) {
                transientFailedInRun.add(provider);
                lastError = "歌手头像服务暂时不可用";
                earliestRetryAt = earliest(earliestRetryAt, retryAt(job, null));
            } finally {
                if (downloadedPath != null && !avatarCommitted) {
                    assets.deleteReadableCache(downloadedPath);
                }
            }
        }

        if (triedChanged) {
            persistTriedProviders(job, tried);
        }
        if (shuttingDown || Thread.currentThread().isInterrupted()) {
            defer(job, "服务关闭中，任务已释放待续跑", Instant.now());
            return;
        }
        if (!rateLimitedInRun.isEmpty() || !transientFailedInRun.isEmpty()) {
            Instant retryAt = earliestRetryAt != null ? earliestRetryAt : Instant.now().plus(Duration.ofMinutes(5));
            if (!transientFailedInRun.isEmpty()) {
                deferRetry(job, lastError != null ? lastError : "歌手头像平台暂时不可用", retryAt);
            } else {
                defer(job, lastError != null ? lastError : "音乐平台处于冷却或限额状态", retryAt);
            }
            return;
        }

        // All enabled primary providers have been definitively searched and missed; try web fallback if enabled.
        processFallback(job, profile, timeout, true);
    }

    private SelectionResult selectBestAvailableProvider(List<MusicProvider> candidates) {
        if (shuttingDown || Thread.currentThread().isInterrupted()) {
            return new SelectionResult(null, null);
        }
        Map<MusicProvider, ProviderRequestRateLimiter.RateSnapshot> snapshots = rateLimiter != null
                ? rateLimiter.snapshotStates() : Map.of();
        MusicProvider best = null;
        long bestAvailableMillis = Long.MAX_VALUE;
        Instant earliestBlockedUntil = null;
        for (MusicProvider candidate : candidates) {
            ProviderRequestRateLimiter.RateSnapshot snapshot = snapshots.get(candidate);
            if (snapshot != null && !snapshot.availableNow()) {
                earliestBlockedUntil = earliest(earliestBlockedUntil, snapshot.blockedUntil());
                continue;
            }
            long guardNextMillis = callGuard != null ? callGuard.nextAvailableAtMillis(candidate) : 0L;
            long dbNextMillis = snapshot != null && snapshot.nextAvailableAt() != null
                    ? snapshot.nextAvailableAt().toEpochMilli() : 0L;
            long effectiveAvailable = Math.max(guardNextMillis, dbNextMillis);
            if (best == null || effectiveAvailable < bestAvailableMillis) {
                best = candidate;
                bestAvailableMillis = effectiveAvailable;
            }
        }
        return new SelectionResult(best, earliestBlockedUntil);
    }

    private List<MusicProvider> orderedEnabledProviders(MusicSourceConfig config) {
        if (config == null || config.providers() == null || config.providers().isEmpty()) {
            return List.of();
        }
        List<MusicProvider> ordered = new ArrayList<>();
        for (MusicProvider provider : MusicProvider.values()) {
            if (config.providers().contains(provider)) {
                ordered.add(provider);
            }
        }
        return ordered;
    }

    private void processLegacyProvider(Job job, ArtistProfileService.Profile profile, MusicSourceConfig config, Duration timeout) {
        MusicProvider jobProvider = job.provider();
        if (jobProvider == null || config.providers() == null || !config.providers().contains(jobProvider)) {
            if (!hasRemainingActiveJobs(profile.artistKey(), job.id())) {
                profiles.markAvatarStatusIfClaimed(profile.artistKey(), "AUTO_INITIAL", "该音乐平台当前未启用",
                        job.id(), job.claimToken());
            }
            finish(job, "SKIPPED", "该音乐平台当前未启用");
            return;
        }
        ArtistMetadataProvider metadataProvider = providers.get(jobProvider);
        if (metadataProvider == null) {
            if (!hasRemainingActiveJobs(profile.artistKey(), job.id())) {
                profiles.markAvatarStatusIfClaimed(profile.artistKey(), "AUTO_INITIAL", "该平台暂不支持歌手头像搜索",
                        job.id(), job.claimToken());
            }
            finish(job, "SKIPPED", "该音乐平台暂不支持歌手头像搜索".replace("该音乐平台", "该平台"));
            return;
        }
        String downloadedPath = null;
        boolean avatarCommitted = false;
        try {
            ExternalArtist candidate = ArtistAvatarMatcher
                    .bestMatch(profile.displayName(), metadataProvider.search(profile.displayName(), 5, timeout))
                    .filter(match -> match.artist().avatarUrl() != null && !match.artist().avatarUrl().isBlank())
                    .map(ArtistAvatarMatcher.Match::artist)
                    .orElse(null);
            if (candidate == null) {
                markUnresolvedIfLastSource(job, profile.artistKey(), "未找到高置信度歌手头像");
                return;
            }
            if (!renewLease(job)) return;
            if (!assets.hasArtistCoverCapacity(5L * 1024 * 1024)) {
                defer(job, "歌手头像缓存已达到容量上限，等待孤儿清理", Instant.now().plus(Duration.ofMinutes(10)));
                return;
            }
            downloadedPath = coverService.downloadArtistAvatar(jobProvider, candidate.avatarUrl(),
                    profile.artistKey() + ":" + job.claimToken(), timeout);
            avatarCommitted = profiles.markAvatarReadyIfClaimed(profile.artistKey(), jobProvider.name(),
                    candidate.externalId(), downloadedPath, job.id(), job.claimToken());
            if (!avatarCommitted) return;
            skipRemainingPendingJobs(profile.artistKey(), job.id());
            finish(job, "COMPLETED", null);
        } catch (ApiException failure) {
            String code = failure.getCode();
            if ("AVATAR_IMAGE_REJECTED".equals(code) || "EXTERNAL_COVER_PLACEHOLDER".equals(code)
                    || "EXTERNAL_COVER_INVALID".equals(code)) {
                log.debug("artist avatar candidate from {} rejected for {}: {}",
                        jobProvider, profile.displayName(), failure.getMessage());
                markUnresolvedIfLastSource(job, profile.artistKey(), failure.getMessage());
                return;
            }
            deferRetry(job, failure.getMessage(), retryAt(job, null));
        } catch (ProviderRateLimitedException failure) {
            log.debug("artist avatar provider {} is cooling down until {}", jobProvider, failure.retryAt());
            defer(job, "音乐平台处于冷却或限额状态", failure.retryAt());
        } catch (ProviderHttpException failure) {
            log.debug("artist avatar provider {} returned HTTP {} for {}", jobProvider,
                    failure.statusCode(), profile.displayName());
            deferRetry(job, "歌手头像平台暂时不可用", retryAt(job, failure));
        } catch (ProviderRateStateUnavailableException failure) {
            log.debug("artist avatar provider rate state unavailable for {}: {}",
                    profile.displayName(), failure.getMessage());
            defer(job, "音乐平台限速状态暂时不可用", Instant.now().plus(Duration.ofMinutes(10)));
        } catch (MusicSourceException failure) {
            log.debug("artist avatar provider {} failed for {}: {}", jobProvider,
                    profile.displayName(), failure.getMessage());
            deferRetry(job, "歌手头像平台暂时不可用", retryAt(job, null));
        } catch (RuntimeException failure) {
            log.debug("artist avatar provider {} failed for {}: {}", jobProvider, profile.displayName(), failure.getMessage());
            deferRetry(job, "歌手头像服务暂时不可用", retryAt(job, null));
        } finally {
            if (downloadedPath != null && !avatarCommitted) {
                assets.deleteReadableCache(downloadedPath);
            }
        }
    }

    private void processFallback(Job job, ArtistProfileService.Profile profile, Duration timeout, boolean singleAutoMode) {
        if (fallbackChain == null || !fallbackChain.isEnabled()) {
            if (singleAutoMode || !hasRemainingActiveJobs(profile.artistKey(), job.id())) {
                profiles.markAvatarStatusIfClaimed(profile.artistKey(), "AUTO_INITIAL",
                        singleAutoMode ? "已选平台均未命中，已使用首字徽章" : "兜底刮削未启用",
                        job.id(), job.claimToken());
            }
            finish(job, "SKIPPED", singleAutoMode ? "已选平台均未命中，已使用首字徽章" : "兜底刮削未启用");
            return;
        }
        String downloadedPath = null;
        boolean avatarCommitted = false;
        try {
            List<String> candidateUrls = fallbackChain.findCandidateUrls(profile.displayName(), timeout);
            if (candidateUrls == null || candidateUrls.isEmpty()) {
                markUnresolvedIfLastSource(job, profile.artistKey(), "未找到高置信度歌手头像");
                return;
            }
            if (!renewLease(job)) return;
            if (!assets.hasArtistCoverCapacity(5L * 1024 * 1024)) {
                defer(job, "歌手头像缓存已达到容量上限，等待孤儿清理", Instant.now().plus(Duration.ofMinutes(10)));
                return;
            }
            String acceptedHost = null;
            for (String candidateUrl : candidateUrls) {
                try {
                    downloadedPath = coverService.downloadFallbackArtistAvatar(
                            candidateUrl, profile.artistKey() + ":" + job.claimToken(), timeout);
                    acceptedHost = java.net.URI.create(candidateUrl).getHost();
                    break;
                } catch (RuntimeException failure) {
                    log.debug("fallback avatar candidate failed for {} ({}): {}",
                            profile.displayName(), candidateUrl, failure.getMessage());
                }
            }
            if (downloadedPath == null) {
                markUnresolvedIfLastSource(job, profile.artistKey(), "兜底图源头像未通过质量或安全校验");
                return;
            }
            avatarCommitted = profiles.markAvatarReadyIfClaimed(profile.artistKey(), "WEB_FALLBACK",
                    acceptedHost != null ? acceptedHost : "web", downloadedPath, job.id(), job.claimToken());
            if (!avatarCommitted) return;
            skipRemainingPendingJobs(profile.artistKey(), job.id());
            finish(job, "COMPLETED", null);
        } catch (ProviderRateLimitedException failure) {
            log.debug("artist avatar fallback rate limited for {}: {}", profile.displayName(), failure.getMessage());
            defer(job, failure.getMessage(), failure.retryAt());
        } catch (RuntimeException failure) {
            log.debug("artist avatar fallback failed for {}: {}", profile.displayName(), failure.getMessage());
            deferRetry(job, "歌手头像兜底服务暂时不可用", retryAt(job, null));
        } finally {
            if (downloadedPath != null && !avatarCommitted) {
                assets.deleteReadableCache(downloadedPath);
            }
        }
    }

    private void markUnresolvedIfLastSource(Job job, String artistKey, String reason) {
        if ("AUTO".equalsIgnoreCase(job.providerCode()) || !hasRemainingActiveJobs(artistKey, job.id())) {
            profiles.markAvatarStatusIfClaimed(artistKey, "AUTO_INITIAL", reason, job.id(), job.claimToken());
        }
        finish(job, "SKIPPED", reason != null ? reason : "未找到肖像，已自动使用首字徽章");
    }

    private boolean hasRemainingActiveJobs(String artistKey, long currentJobId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM artist_avatar_jobs
                    WHERE artist_key=? AND id<>? AND status IN ('PENDING', 'PROCESSING')
                    """, Integer.class, artistKey, currentJobId);
            return count != null && count > 0;
        } catch (DataAccessException failure) {
            return false;
        }
    }

    private void skipRemainingPendingJobs(String artistKey, long currentJobId) {
        try {
            jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET status='SKIPPED', last_error='已从其他平台获取头像', updated_at=now()
                    WHERE artist_key=? AND id<>? AND status='PENDING'
                    """, artistKey, currentJobId);
        } catch (DataAccessException ignored) {
            // Non-critical optimization; remaining jobs will also observe READY avatarPath if claimed later.
        }
    }

    private void persistTriedProviders(Job job, Set<String> tried) {
        if (tried == null || tried.isEmpty()) return;
        try {
            jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET tried_providers=?, updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                    """, tried.toArray(String[]::new), job.id(), job.claimToken());
        } catch (DataAccessException ignored) {}
    }

    private Job claimNext() {
        try {
            int effectiveDailyLimit = 3000;
            try {
                MusicSourceConfig config = configService != null ? configService.getConfig() : null;
                if (config != null && config.dailyLimit() > 0) {
                    effectiveDailyLimit = config.dailyLimit();
                }
            } catch (RuntimeException ignored) {}
            java.sql.Date todayUtc = java.sql.Date.valueOf(java.time.LocalDate.now(ZoneOffset.UTC));
            String claimToken = java.util.UUID.randomUUID().toString();
            List<Job> claimed = jdbc.query("""
                    WITH candidate AS (
                        SELECT job.id
                        FROM artist_avatar_jobs job
                        LEFT JOIN music_provider_rate_state rate ON rate.provider = job.provider
                        WHERE (
                                (job.status = 'PENDING' AND job.next_run_at <= now())
                                OR (job.status = 'PROCESSING'
                                    AND COALESCE(job.lease_until, job.updated_at) <= now())
                              )
                          AND (
                                job.provider IN ('AUTO', 'FALLBACK')
                                OR rate.provider IS NULL
                                OR (
                                    (rate.cooldown_until IS NULL OR rate.cooldown_until <= now())
                                    AND (rate.window_date_utc < ? OR rate.request_count < ?)
                                )
                              )
                          AND (
                                job.provider <> 'FALLBACK'
                                OR NOT EXISTS (
                                    SELECT 1
                                    FROM artist_avatar_jobs primary_job
                                    WHERE primary_job.artist_key = job.artist_key
                                      AND primary_job.tier > job.tier
                                      AND primary_job.status IN ('PENDING', 'PROCESSING')
                                )
                              )
                          AND NOT EXISTS (
                                SELECT 1
                                FROM artist_avatar_jobs active
                                WHERE active.artist_key = job.artist_key
                                  AND active.id <> job.id
                                  AND active.status = 'PROCESSING'
                                  AND active.lease_until > now()
                              )
                        ORDER BY job.priority DESC, job.tier DESC, job.id ASC
                        FOR UPDATE OF job SKIP LOCKED
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
                              job.claim_token::text AS claim_token, job.tried_providers
                    """, (rs, index) -> {
                        String code = rs.getString("provider");
                        MusicProvider parsed = "AUTO".equalsIgnoreCase(code) || "FALLBACK".equalsIgnoreCase(code)
                                ? null : MusicProvider.parse(code);
                        return new Job(rs.getLong("id"), rs.getString("artist_key"),
                                code, parsed, rs.getInt("attempts"), rs.getString("claim_token"),
                                extractTriedProviders(rs));
                    },
                    todayUtc, effectiveDailyLimit, claimToken);
            return claimed.stream().findFirst().orElse(null);
        } catch (DataAccessException failure) {
            log.debug("artist avatar queue unavailable: {}", failure.getMessage());
            return null;
        }
    }

    private static List<String> extractTriedProviders(ResultSet rs) {
        try {
            Array array = rs.getArray("tried_providers");
            if (array == null) return List.of();
            Object raw = array.getArray();
            if (raw instanceof Object[] values) {
                List<String> out = new ArrayList<>(values.length);
                for (Object val : values) {
                    if (val != null) {
                        String cleaned = String.valueOf(val).trim();
                        if (!cleaned.isEmpty()) out.add(cleaned);
                    }
                }
                return out;
            }
        } catch (SQLException | RuntimeException ignored) {}
        return List.of();
    }

    private void finish(Job job, String status, String error) {
        if (shuttingDown || Thread.currentThread().isInterrupted()) return;
        try {
            jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET status=?, last_error=?, lease_until=NULL, claim_token=NULL, updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                    """, status, clean(error), job.id(), job.claimToken());
        } catch (DataAccessException failure) {
            log.debug("artist avatar finish skipped: {}", failure.getMessage());
        }
    }

    private boolean renewLease(Job job) {
        if (shuttingDown || Thread.currentThread().isInterrupted()) return false;
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
        if (shuttingDown || Thread.currentThread().isInterrupted()) return;
        try {
            jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET status='PENDING', next_run_at=?,
                        lease_until=NULL, claim_token=NULL, last_error=?, updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                    """, OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC), reason, job.id(), job.claimToken());
        } catch (DataAccessException failure) {
            log.debug("artist avatar defer skipped: {}", failure.getMessage());
        }
    }

    private void deferRetry(Job job, String reason, Instant retryAt) {
        if (shuttingDown) return;
        try {
            boolean willFail = (job.attempts + 1 >= MAX_ATTEMPTS);
            if (willFail && ("AUTO".equalsIgnoreCase(job.providerCode())
                    || !hasRemainingActiveJobs(job.artistKey(), job.id()))) {
                profiles.markAvatarStatusIfClaimed(job.artistKey(), "AUTO_INITIAL", reason, job.id(), job.claimToken());
            }
            jdbc.update("""
                    UPDATE artist_avatar_jobs
                    SET status=CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                        attempts=attempts+1,
                        next_run_at=CASE WHEN attempts + 1 >= ? THEN now() ELSE ? END,
                        lease_until=NULL, claim_token=NULL, last_error=?, updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND claim_token=?::uuid
                    """, MAX_ATTEMPTS, MAX_ATTEMPTS,
                    OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC), clean(reason), job.id(), job.claimToken());
        } catch (DataAccessException failure) {
            log.debug("artist avatar deferRetry skipped: {}", failure.getMessage());
        }
    }

    private Instant retryAt(Job job, ProviderHttpException failure) {
        Instant now = Instant.now();
        if (failure != null && failure.statusCode() == 429) {
            Instant minimum = now.plusSeconds(15 * 60L);
            return failure.retryAfter() != null && failure.retryAfter().isAfter(minimum)
                    ? failure.retryAfter() : minimum;
        }
        if (failure != null && failure.statusCode() == 403) return now.plus(Duration.ofHours(6));
        return now.plus(Duration.ofSeconds(switch (Math.min(job.attempts + 1, 4)) {
            case 1 -> 2 * 60L;
            case 2 -> 10 * 60L;
            case 3 -> 60 * 60L;
            default -> 6 * 60 * 60L;
        }));
    }

    private static Instant earliest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.isBefore(current)) return candidate;
        return current;
    }

    private record SelectionResult(MusicProvider selected, Instant earliestBlockedUntil) {}

    private record Job(long id, String artistKey, String providerCode, MusicProvider provider, int attempts,
                       String claimToken, List<String> triedProviders) {}

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String compact = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return compact.length() <= 1000 ? compact : compact.substring(0, 1000);
    }
}
