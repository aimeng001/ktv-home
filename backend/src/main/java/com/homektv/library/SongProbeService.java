package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
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

    // Single-flight 并发探测锁，防止并发点播同一首歌曲发起重复的 FFprobe 进程
    private final ConcurrentHashMap<Long, Object> probeLocks = new ConcurrentHashMap<>();

    public SongProbeService(SongRepository songRepo,
                            SongFileRepository fileRepo,
                            MediaProbeStage mediaProbeStage,
                            SongAvailabilityPolicy availabilityPolicy) {
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.mediaProbeStage = mediaProbeStage;
        this.availabilityPolicy = availabilityPolicy;
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

        Object lock = probeLocks.computeIfAbsent(songId, k -> new Object());
        synchronized (lock) {
            try {
                // 双重检查
                if (availabilityPolicy.isPlayable(song)) {
                    return;
                }
                probeAndPersist(song);
            } finally {
                probeLocks.remove(songId);
            }
        }
    }

    /**
     * 即时执行探测并持久化，供管理端点显式触发。
     */
    public boolean probeAndPersist(Long songId) {
        if (songId == null) {
            throw new ApiException("INVALID_ARGUMENT", "歌曲ID不能为空");
        }
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));
        return probeAndPersist(song);
    }

    /**
     * 即时执行探测并持久化，供管理端点显式触发。
     */
    public boolean probeAndPersist(Song song) {
        Long songId = song.getId();
        List<SongFile> files = fileRepo.findBySongIdOrderByPriorityDesc(songId);
        if (files.isEmpty()) {
            throw new ApiException("NO_MEDIA_SOURCE", "歌曲无有效媒体源文件，无法探测");
        }

        SongFile targetFile = files.stream()
                .filter(f -> f.isProbePending() || f.getMediaType() == null || "pending_probe".equalsIgnoreCase(f.getMediaType()))
                .findFirst()
                .orElse(files.get(0));

        Path filePath = Path.of(targetFile.getFilePath());
        if (!Files.exists(filePath)) {
            log.warn("探测失败：媒体文件不存在 {}", filePath);
            throw new ApiException("FILE_MISSING", "媒体文件不存在: " + filePath.getFileName());
        }

        log.info("触发按需即时探测: songId={}, path={}", songId, filePath);
        MediaProbeStage.Result result = mediaProbeStage.probe(filePath);
        if (result.failure() != null || result.probe() == null) {
            String errorMsg = result.failure() != null ? result.failure().getMessage() : "未能解析到媒体流";
            log.warn("按需即时探测失败: songId={}, error={}", songId, errorMsg);
            throw new ApiException("SONG_PROBE_FAILED", "歌曲媒体探测失败: " + errorMsg);
        }

        MediaProbe probe = result.probe();
        applyProbeToSongFile(targetFile, probe);
        fileRepo.save(targetFile);

        applyProbeToSong(song, targetFile, probe);
        songRepo.save(song);

        log.info("按需即时探测成功并持久化: songId={}, format={}, mediaType={}, durationMs={}, audioLayout={}",
                songId, targetFile.getFormat(), targetFile.getMediaType(), probe.durationMs(), targetFile.getAudioLayout());
        return true;
    }

    private void applyProbeToSongFile(SongFile sf, MediaProbe probe) {
        String mediaType = MediaClassifier.classify(probe);
        sf.setMediaType(mediaType);
        sf.setResolution(probe.resolution());
        sf.setAudioTracks(probe.audioTracks());
        if (probe.audioTracks() >= 2) {
            sf.setAudioLayout(AudioLayout.DUAL_TRACK);
        }
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
        boolean hasVocal = probe.audioTracks() >= 2 || (sf.getAudioLayout() != null && !sf.getAudioLayout().equals(AudioLayout.NORMAL_STEREO.name()));
        song.setHasVocalTrack(hasVocal);
    }
}
