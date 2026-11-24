package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.homektv.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按需探测服务：为尚未完成全量探测的歌曲提供即时 FFprobe 探测并持久化，
 * 使得未探测的曲目在被点播或管理探测时能够即时达到就绪状态。
 */
@Service
public class SongProbeService {

    private static final Logger log = LoggerFactory.getLogger(SongProbeService.class);

    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final MediaProbeStage mediaProbeStage;
    private final SongAvailabilityPolicy availabilityPolicy;

    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private AppProperties appProperties;

    // 探测失败文件短期冷却：连续失败 >= 2 次后进入 5 分钟冷却，避免损坏媒体反复被点播时同步阻塞 HTTP 线程
    private static final Duration PROBE_FAILURE_COOLDOWN = Duration.ofMinutes(5);
    private static final int PROBE_FAILURE_COOLDOWN_THRESHOLD = 2;
    private static final int MAX_FAILURE_COOLDOWN_ENTRIES = 5000;
    private record FailureState(int failures, Instant cooldownUntil) {}
    private final ConcurrentHashMap<Long, FailureState> failedProbeCooldowns = new ConcurrentHashMap<>();

    // Single-flight 并发探测锁（带等待计数），防止并发探测同一首歌曲以及失败退锁时丢锁
    private final ConcurrentHashMap<Long, LockEntry> probeLocks = new ConcurrentHashMap<>();

    private static final class LockEntry {
        int waiters = 0;
    }

    public SongProbeService(SongRepository songRepo,
                            SongFileRepository fileRepo,
                            MediaProbeStage mediaProbeStage,
                            SongAvailabilityPolicy availabilityPolicy) {
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.mediaProbeStage = mediaProbeStage;
        this.availabilityPolicy = availabilityPolicy;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionTemplate(org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAppProperties(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    private LockEntry acquireLock(Long songId) {
        return probeLocks.compute(songId, (k, existing) -> {
            LockEntry entry = existing != null ? existing : new LockEntry();
            entry.waiters++;
            return entry;
        });
    }

    private void releaseLock(Long songId, LockEntry entry) {
        probeLocks.computeIfPresent(songId, (k, existing) -> {
            if (existing != entry) {
                return existing;
            }
            existing.waiters--;
            return existing.waiters <= 0 ? null : existing;
        });
    }

    /**
     * 确保歌曲处于可播放状态。如果未就绪且存在待探测文件，则执行即时探测。
     */
    public void ensurePlayable(Long songId) {
        if (songId == null) return;

        Song song = songRepo.findById(songId).orElse(null);
        if (song == null || "file_missing".equals(song.getStatus())) {
            return;
        }

        if (availabilityPolicy.isPlayable(song)) {
            return;
        }

        LockEntry lock = acquireLock(songId);
        try {
            synchronized (lock) {
                // 双重检查
                Song current = songRepo.findById(songId).orElse(song);
                if (availabilityPolicy.isPlayable(current)) {
                    return;
                }
                probeAndPersistInternal(current, true);
            }
        } finally {
            releaseLock(songId, lock);
        }
    }

    /**
     * 即时执行探测并持久化，供管理端点显式触发。
     */
    public boolean probeAndPersist(Long songId) {
        if (songId == null) {
            throw new ApiException("INVALID_ARGUMENT", "歌曲ID不能为空");
        }
        LockEntry lock = acquireLock(songId);
        try {
            synchronized (lock) {
                Song song = songRepo.findById(songId)
                        .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));
                return probeAndPersistInternal(song, false);
            }
        } finally {
            releaseLock(songId, lock);
        }
    }

    /**
     * 即时执行探测并持久化，供管理端点显式触发。
     */
    public boolean probeAndPersist(Song song) {
        if (song == null || song.getId() == null) {
            throw new ApiException("INVALID_ARGUMENT", "歌曲ID不能为空");
        }
        LockEntry lock = acquireLock(song.getId());
        try {
            synchronized (lock) {
                return probeAndPersistInternal(song, false);
            }
        } finally {
            releaseLock(song.getId(), lock);
        }
    }

    private boolean probeAndPersistInternal(Song song, boolean honorCooldown) {
        Long songId = song.getId();
        List<SongFile> files = fileRepo.findBySongIdOrderByPriorityDesc(songId);
        if (files.isEmpty()) {
            throw new ApiException("NO_MEDIA_SOURCE", "歌曲无有效媒体源文件，无法探测");
        }

        SongFile targetFile = files.stream()
                .filter(f -> f.isProbePending() || f.getMediaType() == null || "pending_probe".equalsIgnoreCase(f.getMediaType()))
                .findFirst()
                .orElse(files.get(0));

        Long fileId = targetFile.getId();
        Instant now = Instant.now();
        if (honorCooldown && fileId != null) {
            FailureState failureState = failedProbeCooldowns.get(fileId);
            if (failureState != null
                    && failureState.cooldownUntil() != null
                    && now.isBefore(failureState.cooldownUntil())) {
                throw new ApiException("SONG_PROBE_FAILED", "歌曲媒体最近探测失败，请稍后重试");
            }
        } else if (!honorCooldown && fileId != null) {
            failedProbeCooldowns.remove(fileId);
        }

        Path rawPath = Path.of(targetFile.getFilePath());
        Path filePath = appProperties != null
                ? LibraryModePolicy.requireReadablePathInsideActiveLibrary(appProperties, rawPath)
                : rawPath;
        if (!Files.exists(filePath)) {
            log.warn("探测失败：媒体文件不存在 {}", filePath);
            throw new ApiException("FILE_MISSING", "媒体文件不存在: " + filePath.getFileName());
        }

        log.info("触发按需即时探测: songId={}, path={}", songId, filePath);
        MediaProbeStage.Result result = mediaProbeStage.probe(filePath);
        if (result.failure() != null || result.probe() == null) {
            if (fileId != null) {
                if (failedProbeCooldowns.size() >= MAX_FAILURE_COOLDOWN_ENTRIES) {
                    failedProbeCooldowns.clear();
                }
                failedProbeCooldowns.compute(fileId, (k, existing) -> {
                    int nextCount = (existing == null ? 0 : existing.failures()) + 1;
                    Instant until = nextCount >= PROBE_FAILURE_COOLDOWN_THRESHOLD
                            ? now.plus(PROBE_FAILURE_COOLDOWN)
                            : null;
                    return new FailureState(nextCount, until);
                });
            }
            String errorMsg = result.failure() != null ? result.failure().getMessage() : "未能解析到媒体流";
            log.warn("按需即时探测失败: songId={}, error={}", songId, errorMsg);
            throw new ApiException("SONG_PROBE_FAILED", "歌曲媒体探测失败: " + errorMsg);
        }
        if (fileId != null) {
            failedProbeCooldowns.remove(fileId);
        }

        MediaProbe probe = result.probe();
        persistProbeResult(song, targetFile, probe);

        log.info("按需即时探测成功并持久化: songId={}, format={}, mediaType={}, durationMs={}, audioLayout={}",
                songId, targetFile.getFormat(), targetFile.getMediaType(), probe.durationMs(), targetFile.getAudioLayout());
        return true;
    }

    private void persistProbeResult(Song fallbackSong, SongFile fallbackFile, MediaProbe probe) {
        Runnable writeAction = () -> {
            SongFile latestFile = fallbackFile.getId() != null
                    ? fileRepo.findById(fallbackFile.getId()).orElse(fallbackFile)
                    : fallbackFile;
            applyProbeToSongFile(latestFile, probe);
            fileRepo.save(latestFile);

            Song latestSong = fallbackSong.getId() != null
                    ? songRepo.findById(fallbackSong.getId()).orElse(fallbackSong)
                    : fallbackSong;
            applyProbeToSong(latestSong, latestFile, probe);
            songRepo.save(latestSong);
        };
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> writeAction.run());
        } else {
            writeAction.run();
        }
    }

    private void applyProbeToSongFile(SongFile sf, MediaProbe probe) {
        if (probe.audioTracks() >= 2) {
            sf.setAudioLayout(AudioLayout.DUAL_TRACK);
        }
        String mediaType = MediaClassifier.classify(probe, sf.getAudioLayout());
        sf.setMediaType(mediaType);
        sf.setResolution(probe.resolution());
        sf.setAudioTracks(probe.audioTracks());
        sf.setAudioCodec(probe.audioCodec());
        sf.setVideoCodec(probe.videoCodec());
        sf.setProbePending(false);
        sf.setValid(true);
    }

    private void applyProbeToSong(Song song, SongFile sf, MediaProbe probe) {
        if (probe.durationMs() > 0) {
            song.setDurationMs((int) Math.min(Integer.MAX_VALUE, probe.durationMs()));
        }
        if (song.getMediaType() == null || "pending_probe".equalsIgnoreCase(song.getMediaType()) || song.getMediaType().isBlank()) {
            song.setMediaType(sf.getMediaType());
        }
        boolean hasVocal = MediaClassifier.hasVocalTrack(probe, sf.getAudioLayout());
        song.setHasVocalTrack(hasVocal);
    }
}
