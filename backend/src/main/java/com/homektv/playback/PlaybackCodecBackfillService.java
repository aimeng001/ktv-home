package com.homektv.playback;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryModePolicy;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.resource.ResourceBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared bounded on-demand codec prober and PostgreSQL metadata backfill helper.
 * Never mutates source files; only persists video_codec / audio_codec back to song_files.
 */
@Service
public class PlaybackCodecBackfillService {
    private static final Logger log = LoggerFactory.getLogger(PlaybackCodecBackfillService.class);
    private static final int MAX_CACHE_ENTRIES = 2000;
    private static final String MULTICHANNEL_SENTINEL = "multichannel";

    public record ProbedCodecs(String videoCodec, String audioCodec) {
        public boolean isMultichannelAudio() {
            return MULTICHANNEL_SENTINEL.equals(audioCodec);
        }
    }

    private final SongFileRepository songFileRepository;
    private final AppProperties props;
    private final FFprobeService ffprobeService;
    private final ResourceBudget resourceBudget;
    private final TransactionTemplate requiresNewTx;
    private final Map<Long, ProbedCodecs> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ProbedCodecs> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private final ConcurrentHashMap<Long, Object> probeLocks = new ConcurrentHashMap<>();

    @Autowired
    public PlaybackCodecBackfillService(
            SongFileRepository songFileRepository,
            AppProperties props,
            @Autowired(required = false) FFprobeService ffprobeService,
            @Autowired(required = false) ResourceBudget resourceBudget,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.songFileRepository = songFileRepository;
        this.props = props;
        this.ffprobeService = ffprobeService;
        this.resourceBudget = resourceBudget;
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = template;
        } else {
            this.requiresNewTx = null;
        }
    }

    public static boolean isMissingCodec(String codec) {
        if (codec == null) return true;
        String trimmed = codec.trim();
        return trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed);
    }

    /**
     * Ensures missing videoCodec (and audioCodec) on an existing library video record is probed
     * on demand and written back to song_files without touching source files.
     */
    public void backfillMissingVideoCodec(SongFile sf) {
        if (sf == null || sf.getId() == null || ffprobeService == null || props == null) return;
        if (!PlaybackTransportPolicy.isVideo(sf) || !isMissingCodec(sf.getVideoCodec())) return;

        ProbedCodecs cached = cache.get(sf.getId());
        if (cached != null) {
            applyToSongFile(sf, cached);
            return;
        }
        String rawPath = sf.getFilePath();
        if (rawPath == null || rawPath.isBlank()) return;

        Path safePath;
        try {
            safePath = LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, Path.of(rawPath));
        } catch (RuntimeException ignored) {
            return;
        }
        if (!Files.isRegularFile(safePath)) return;

        ProbedCodecs probed = probeAndCache(sf, safePath.toFile());
        if (probed != null) {
            applyToSongFile(sf, probed);
        }
    }

    /**
     * Probes an already-validated file on demand (used by StreamController), caches the codecs,
     * and backfills song_files when persistence is configured.
     */
    public ProbedCodecs probeAndCache(SongFile sf, File file) {
        if (sf == null || sf.getId() == null || file == null || ffprobeService == null) {
            return null;
        }
        Long fileId = sf.getId();
        ProbedCodecs cached = cache.get(fileId);
        if (cached != null) {
            applyToSongFile(sf, cached);
            return cached;
        }
        Object lock = probeLocks.computeIfAbsent(fileId, ignored -> new Object());
        try {
            synchronized (lock) {
                cached = cache.get(fileId);
                if (cached != null) {
                    applyToSongFile(sf, cached);
                    return cached;
                }
                ProbedCodecs probed = runProbe(file);
                if (probed == null) return null;
                cache.put(fileId, probed);
                applyToSongFile(sf, probed);
                persistBackfill(fileId, probed);
                return probed;
            }
        } finally {
            probeLocks.remove(fileId, lock);
        }
    }

    public ProbedCodecs getCached(Long fileId) {
        return fileId == null ? null : cache.get(fileId);
    }

    private ProbedCodecs runProbe(File file) {
        try (ResourceBudget.Permit probePermit = resourceBudget != null
                ? resourceBudget.tryAcquireProbe(Duration.ofMillis(150)).orElse(null)
                : null) {
            if (resourceBudget != null && probePermit == null) {
                return null;
            }
            MediaProbe probe = ffprobeService.probe(file.toPath());
            if (probe == null) return null;

            String videoCodec = normalizeCodec(probe.videoCodec());
            boolean multiChannel = probe.audioStreams() != null && probe.audioStreams().stream()
                    .anyMatch(stream -> stream != null && stream.channels() > 2);
            String audioCodec = multiChannel
                    ? MULTICHANNEL_SENTINEL
                    : normalizeCodec(probe.audioCodec());
            return new ProbedCodecs(videoCodec, audioCodec);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyToSongFile(SongFile sf, ProbedCodecs probed) {
        if (probed.videoCodec() != null && isMissingCodec(sf.getVideoCodec())) {
            sf.setVideoCodec(probed.videoCodec());
        }
        if (probed.audioCodec() != null && !probed.isMultichannelAudio() && isMissingCodec(sf.getAudioCodec())) {
            sf.setAudioCodec(probed.audioCodec());
        }
    }

    private void persistBackfill(Long fileId, ProbedCodecs probed) {
        if (songFileRepository == null || requiresNewTx == null) return;
        String dbVideo = probed.videoCodec();
        String dbAudio = probed.isMultichannelAudio() ? null : probed.audioCodec();
        if (dbVideo == null && dbAudio == null) return;
        try {
            requiresNewTx.executeWithoutResult(status ->
                    songFileRepository.backfillProbedCodecs(fileId, dbVideo, dbAudio));
        } catch (RuntimeException failure) {
            log.debug("回写探测编码元数据失败（忽略不影响本次播放）：fileId={} - {}", fileId, failure.getMessage());
        }
    }

    private static String normalizeCodec(String codec) {
        if (codec == null) return null;
        String trimmed = codec.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || "unknown".equals(trimmed) ? null : trimmed;
    }
}
