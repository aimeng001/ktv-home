package com.homektv.playback;

import com.homektv.config.AppProperties;
import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.domain.PlaybackVariantStatus;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryModePolicy;
import com.homektv.library.MediaProbeStage;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.resource.ResourceBudget;
import com.homektv.repo.PlaybackVariantRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Retains legacy playback-variant reads and job compatibility. Current source
 * selection is handled by PlaybackResolutionService.
 */
@Service
public class PlaybackVariantService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackVariantService.class);
    private static final int MAX_PROCESS_OUTPUT = 64 * 1024;
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final long DEFAULT_MAX_CACHE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final Duration STREAM_LEASE = Duration.ofMinutes(10);

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private final SongFileRepository files;
    private final PlaybackVariantRepository variants;
    private final MediaProbeStage mediaProbeStage;
    private final AppProperties props;
    private final String ffmpegPath;
    private final Duration timeout;
    private final ExecutorService ownedExecutor;
    private final long maxCacheBytes;
    private final ProcessLauncher processLauncher;

    @Autowired
    public PlaybackVariantService(SongFileRepository files,
                                  PlaybackVariantRepository variants,
                                  MediaProbeStage mediaProbeStage,
                                  AppProperties props,
                                  @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath,
                                  @Value("${app.transcode.timeout-seconds:1800}") long timeoutSeconds,
                                  @Value("${app.playback-cache.max-bytes:21474836480}") long maxCacheBytes) {
        this(files, variants, mediaProbeStage, props, ffmpegPath,
                Executors.newFixedThreadPool(2, Thread.ofVirtual().factory()),
                true, PlaybackVariantService::startProcess,
                Duration.ofSeconds(timeoutSeconds), maxCacheBytes);
    }

    /** Compatibility constructor for isolated legacy tests. */
    PlaybackVariantService(SongFileRepository files,
                           PlaybackVariantRepository variants,
                           FFprobeService ffprobe,
                           AppProperties props,
                           String ffmpegPath,
                           Executor executor,
                           ProcessLauncher processLauncher,
                           Duration timeout) {
        this(files, variants, standaloneProbeStage(ffprobe), props, ffmpegPath, executor, false,
                processLauncher, timeout, DEFAULT_MAX_CACHE_BYTES);
    }

    PlaybackVariantService(SongFileRepository files,
                           PlaybackVariantRepository variants,
                           MediaProbeStage mediaProbeStage,
                           AppProperties props,
                           String ffmpegPath,
                           Executor executor,
                           ProcessLauncher processLauncher,
                           Duration timeout) {
        this(files, variants, mediaProbeStage, props, ffmpegPath, executor,
                processLauncher, timeout, DEFAULT_MAX_CACHE_BYTES);
    }

    PlaybackVariantService(SongFileRepository files,
                           PlaybackVariantRepository variants,
                           MediaProbeStage mediaProbeStage,
                           AppProperties props,
                           String ffmpegPath,
                           Executor executor,
                           ProcessLauncher processLauncher,
                           Duration timeout,
                           long maxCacheBytes) {
        this(files, variants, mediaProbeStage, props, ffmpegPath, executor, false,
                processLauncher, timeout, maxCacheBytes);
    }

    private PlaybackVariantService(SongFileRepository files,
                                   PlaybackVariantRepository variants,
                                   MediaProbeStage mediaProbeStage,
                                   AppProperties props,
                                   String ffmpegPath,
                                   Executor executor,
                                   boolean owned,
                                   ProcessLauncher processLauncher,
                                   Duration timeout,
                                   long maxCacheBytes) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("playback transcode timeout must be positive");
        }
        this.files = files;
        this.variants = variants;
        this.mediaProbeStage = java.util.Objects.requireNonNull(mediaProbeStage, "mediaProbeStage");
        this.props = props;
        this.ffmpegPath = ffmpegPath;
        // Only the Spring constructor sets owned=true, and it supplies a fixed-thread-pool ExecutorService.
        this.ownedExecutor = owned ? (ExecutorService) executor : null;
        this.processLauncher = processLauncher;
        this.timeout = timeout;
        if (maxCacheBytes <= 0) throw new IllegalArgumentException("playback cache max bytes must be positive");
        this.maxCacheBytes = maxCacheBytes;
    }

    private static MediaProbeStage standaloneProbeStage(FFprobeService ffprobe) {
        return new MediaProbeStage(ffprobe, new ResourceBudget(2, 1, 4, () -> false));
    }

    /** Resolve a cache path only after a READY row has been persisted. */
    public Path readableCachePath(PlaybackVariant variant) {
        if (variant == null || !variant.isReady()) {
            throw new ApiException("PLAYBACK_NOT_READY", "播放变体尚未就绪");
        }
        Path root = cacheRoot();
        Path candidate = root.resolve(variant.getCachePath()).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException("PLAYBACK_CACHE_MISSING", "播放缓存不存在");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new ApiException("PLAYBACK_CACHE_PATH_INVALID", "播放缓存路径越界");
            }
            variant.touchAccess(OffsetDateTime.now(), OffsetDateTime.now().plus(STREAM_LEASE));
            variants.save(variant);
            return realCandidate;
        } catch (IOException e) {
            throw new ApiException("PLAYBACK_CACHE_MISSING", "播放缓存无法读取");
        }
    }

    private void ensureCacheCapacity(long requiredBytes, long keepVariantId) {
        Path root = cacheRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ApiException("PLAYBACK_CACHE_UNAVAILABLE", "播放缓存目录无法创建");
        }

        List<PlaybackVariant> ready = variants.findAll().stream()
                .filter(item -> item.getStatus() == PlaybackVariantStatus.READY)
                .filter(item -> item.getCachePath() != null && !item.getCachePath().isBlank())
                .toList();
        long total = 0;
        for (PlaybackVariant item : ready) {
            Path path = safeCachePath(root, item.getCachePath());
            if (path != null) {
                try { total += Files.size(path); } catch (IOException ignored) { }
            }
        }
        if (total + requiredBytes <= maxCacheBytes) return;

        OffsetDateTime now = OffsetDateTime.now();
        List<PlaybackVariant> victims = new java.util.ArrayList<>(ready);
        victims.sort(java.util.Comparator.comparing(this::lastAccessOrReady));
        for (PlaybackVariant victim : victims) {
            if (keepVariantId == victim.getId()) continue;
            if (victim.hasActiveStreamLease(now)) continue;
            Path path = safeCachePath(root, victim.getCachePath());
            long size = 0;
            try {
                if (path != null) {
                    size = Files.size(path);
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                log.warn("Unable to evict playback cache variant {}: {}", victim.getId(), e.getMessage());
                continue;
            }
            victim.setStatus(PlaybackVariantStatus.STALE);
            variants.save(victim);
            total = Math.max(0, total - size);
            if (total + requiredBytes <= maxCacheBytes) return;
        }
        throw new ApiException("PLAYBACK_CACHE_FULL", "播放缓存空间不足");
    }

    private OffsetDateTime lastAccessOrReady(PlaybackVariant variant) {
        if (variant.getLastAccessAt() != null) return variant.getLastAccessAt();
        if (variant.getReadyAt() != null) return variant.getReadyAt();
        return OffsetDateTime.MIN;
    }

    private Path safeCachePath(Path root, String relativePath) {
        // ensureCacheCapacity filters null and blank cache paths before calling this helper.
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(realRoot) ? realCandidate : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Renews the lease while a long-running HTTP response is still reading the cache file. */
    public void renewStreamLease(Long variantId) {
        if (variantId == null) return;
        try {
            variants.findById(variantId).filter(PlaybackVariant::isReady).ifPresent(variant -> {
                OffsetDateTime now = OffsetDateTime.now();
                variant.touchAccess(now, now.plus(STREAM_LEASE));
                variants.save(variant);
            });
        } catch (RuntimeException failure) {
            // The file descriptor is already open; a failed lease refresh must not truncate the stream.
            log.debug("Unable to renew playback stream lease {}: {}", variantId, failure.getMessage());
        }
    }

    /** Reconciles expired legacy jobs without creating or deleting historical cache files. */
    @Scheduled(
            fixedDelayString = "${app.playback-cache.cleanup-ms:900000}",
            initialDelayString = "${app.playback-cache.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanupCache() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            for (PlaybackVariant variant : variants.findAll()) {
                if (variant.getStatus() == PlaybackVariantStatus.PREPARING
                        && (variant.getLeaseUntil() == null || variant.getLeaseUntil().isBefore(now))) {
                    variant.setStatus(PlaybackVariantStatus.STALE);
                    variants.save(variant);
                }
            }
        } catch (Exception e) {
            log.warn("Legacy playback job reconciliation failed: {}", e.getMessage());
        }
    }
    Path cacheRoot() {
        Path root = Path.of(props.getDataPath()).resolve("playback-cache").toAbsolutePath().normalize();
        LibraryModePolicy.requireCacheOutsideExternalSource(props, root);
        return root;
    }

    void transcodeVariant(Long variantId) {
        PlaybackVariant variant = variants.findById(variantId).orElse(null);
        if (variant == null || variant.getStatus() == PlaybackVariantStatus.READY) return;
        Path temp = null;
        try {
            SongFile source = files.findById(variant.getSourceFileId()).orElseThrow(
                    () -> new ApiException("FILE_NOT_FOUND", "变体源文件不存在"));
            Path input = readableSource(source);
            Path root = cacheRoot();
            Files.createDirectories(root);
            Path output = root.resolve(cacheFileName(variant)).normalize();
            if (!output.startsWith(root)) throw new ApiException("PLAYBACK_CACHE_PATH_INVALID", "播放缓存路径越界");
            temp = root.resolve(variant.getId() + ".part").normalize();
            Files.deleteIfExists(temp);
            Process process = processLauncher.start(List.of(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(),
                    "-map", "0:v:0", "-map", "0:a?",
                    "-map_metadata", "0", "-map_chapters", "0",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "high",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
                    "-movflags", "+faststart", temp.toString()));
            ProcessResult result = run(process);
            if (result.timedOut()) throw new ApiException("TRANSCODE_TIMEOUT", "MV 转码超时");
            if (result.exitCode() != 0 || !Files.isRegularFile(temp) || Files.size(temp) <= 0) {
                throw new ApiException("TRANSCODE_FAILED", result.output().isBlank() ? "MV 转码失败" : result.output());
            }
            MediaProbe outputProbe = mediaProbeStage.probeRequired(temp);
            validateOutput(source, outputProbe);
            ensureCacheCapacity(Files.size(temp), variant.getId());
            moveAtomically(temp, output);
            variant.copyAudioSemanticsFrom(source);
            variant.setFormat("mp4");
            variant.markReady(output.getFileName().toString(), Files.size(output));
            variants.save(variant);
        } catch (Exception failure) {
            String code = failure instanceof ApiException api ? api.getCode() : "TRANSCODE_FAILED";
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            log.warn("Playback variant {} failed: {}", variantId, message);
            PlaybackVariant current = variants.findById(variantId).orElse(null);
            if (current != null) {
                current.markFailed(code, message);
                variants.save(current);
            }
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    private void validateOutput(SongFile source, MediaProbe output) {
        if (output == null || !output.hasVideo()) {
            throw new ApiException("OUTPUT_NO_VIDEO", "转码产物没有可播放的视频轨道");
        }
        if (output.videoCodec() != null && !"h264".equalsIgnoreCase(output.videoCodec())) {
            throw new ApiException("OUTPUT_VIDEO_CODEC_INVALID", "转码产物不是 H.264");
        }
        if (source.getAudioTracks() > 0 && output.audioTracks() < source.getAudioTracks()) {
            throw new ApiException("OUTPUT_AUDIO_TRACK_LOSS", "转码产物丢失音轨");
        }
    }

    private Path readableSource(SongFile source) {
        if (source.getFilePath() == null || source.getFilePath().isBlank()) {
            throw new ApiException("FILE_NOT_FOUND", "媒体路径为空");
        }
        return LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, Path.of(source.getFilePath()));
    }

    private String cacheFileName(PlaybackVariant variant) {
        return variant.getSourceFileId() + "-" + variant.getSourceFingerprint() + ".mp4";
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); } catch (IOException e) { throw new ApiException("FILE_NOT_FOUND", "无法读取媒体大小"); }
    }

    private static long modifiedMillis(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException e) { throw new ApiException("FILE_NOT_FOUND", "无法读取媒体修改时间"); }
    }

    private static OffsetDateTime fileMtime(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().atOffset(ZoneOffset.UTC); }
        catch (IOException e) { return OffsetDateTime.now(ZoneOffset.UTC); }
    }

    private static String workerId() { return "playback-" + UUID.randomUUID(); }
    private OffsetDateTime leaseUntil() { return OffsetDateTime.now().plus(timeout).plusMinutes(5); }

    private static void moveAtomically(Path temp, Path output) throws IOException {
        try {
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ProcessResult run(Process process) throws IOException, InterruptedException {
        FutureTask<String> output = new FutureTask<>(() -> readOutput(process));
        Thread.ofVirtual().name("playback-ffmpeg-output-reader").start(output);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new ProcessResult(-1, readTask(output), true);
            }
            return new ProcessResult(process.exitValue(), readTask(output), false);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
        }
    }

    private static String readTask(FutureTask<String> task) throws IOException, InterruptedException {
        try { return task.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException("读取 ffmpeg 输出失败", e.getCause()); }
        catch (java.util.concurrent.TimeoutException e) { throw new IOException("读取 ffmpeg 输出超时", e); }
    }

    private static String readOutput(Process process) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(process.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total < MAX_PROCESS_OUTPUT) {
                    int keep = Math.min(read, MAX_PROCESS_OUTPUT - total);
                    output.write(buffer, 0, keep);
                    total += keep;
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) { }
}
