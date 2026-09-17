package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Creates a TV-compatible H.264/AAC derivative without touching the source file. */
@Service
public class TranscodeService {
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws java.io.IOException;
    }

    private final SongFileRepository files;
    private final AppProperties props;
    private final String ffmpegPath;
    private final ProcessLauncher processLauncher;
    private final Duration timeout;

    /** Compatibility constructor for callers that use the original managed-mode service directly. */
    public TranscodeService(SongFileRepository files) {
        this(files, new AppProperties(), "ffmpeg", TranscodeService::startProcess,
                MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    @Autowired
    public TranscodeService(SongFileRepository files, AppProperties props,
                            @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath,
                            @Value("${app.transcode.timeout-seconds:1800}") long timeoutSeconds) {
        this(files, props, ffmpegPath, TranscodeService::startProcess,
                MediaProcessRunner.fromSeconds(timeoutSeconds));
    }

    public TranscodeService(SongFileRepository files, AppProperties props) {
        this(files, props, "ffmpeg", TranscodeService::startProcess,
                MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    TranscodeService(SongFileRepository files, AppProperties props, Duration timeout) {
        this(files, props, "ffmpeg", TranscodeService::startProcess, timeout);
    }

    TranscodeService(SongFileRepository files, AppProperties props, String ffmpegPath,
                     ProcessLauncher processLauncher) {
        this(files, props, ffmpegPath, processLauncher, MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    TranscodeService(SongFileRepository files, AppProperties props, String ffmpegPath,
                     ProcessLauncher processLauncher, Duration timeout) {
        this.files = files;
        this.props = props;
        this.ffmpegPath = ffmpegPath;
        this.processLauncher = processLauncher;
        this.timeout = timeout;
    }

    public Result transcodeSong(Long songId) {
        LibraryModePolicy.requireManaged(props, "转码源文件");
        List<SongFile> sources = files.findBySongIdOrderByPriorityDesc(songId);
        SongFile source = sources.stream().findFirst()
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "歌曲没有可转码文件"));
        if (LibraryModePolicy.EXTERNAL_FILE_ROLE.equals(source.getFileRole())) {
            throw new ApiException(LibraryModePolicy.EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：禁止转码外部曲库源文件");
        }
        Path input = LibraryModePolicy.requireReadablePathInsideActiveLibrary(
                props, Path.of(source.getFilePath()));
        Path desired = input.resolveSibling(stripExtension(input.getFileName().toString()) + ".mkv");
        desired = LibraryModePolicy.requirePathInsideActiveLibrary(props, desired);
        SongFile existing = files.findByFilePath(desired.toString()).orElse(null);
        try {
            if (existing != null && Files.isReadable(desired) && Files.size(desired) > 0) {
                return new Result(existing.getId(), desired.toString(), Files.size(desired));
            }
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
        Path output;
        try {
            output = OutputPathReservation.reserve(desired);
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
        boolean completed = false;
        try {
            Process process = processLauncher.start(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-map", "0:v:0?", "-map", "0:a?",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "high",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-c:s", "copy", output.toString()));
            MediaProcessRunner.Result result = MediaProcessRunner.run(process, timeout);
            if (result.timedOut()) throw new ApiException("TRANSCODE_TIMEOUT", "ffmpeg 转码超时");
            String log = result.output();
            int code = result.exitCode();
            if (code != 0 || !Files.isReadable(output) || Files.size(output) == 0) {
                throw new ApiException("TRANSCODE_FAILED", log == null || log.isBlank()
                        ? "ffmpeg 转码失败" : log);
            }
            SongFile derivative = existing != null ? existing : new SongFile();
            derivative.setSongId(source.getSongId());
            derivative.setFilePath(output.toString());
            derivative.setFormat("matroska");
            derivative.setAudioTracks(source.getAudioTracks());
            derivative.setAudioLayout(source.getAudioLayout());
            derivative.setOriginalTrackIndex(source.getOriginalTrackIndex());
            derivative.setAccompanimentTrackIndex(source.getAccompanimentTrackIndex());
            derivative.setOriginalChannel(source.getOriginalChannel());
            derivative.setAccompanimentChannel(source.getAccompanimentChannel());
            derivative.setVocalTrackIndex(source.getVocalTrackIndex());
            derivative.setVocalConfidence(source.getVocalConfidence());
            derivative.setResolution(source.getResolution());
            derivative.setFileSize(Files.size(output));
            derivative.setFileMtime(java.time.OffsetDateTime.now());
            derivative.setPriority(source.getPriority() + 100);
            // 转码产物尚未 FFprobe：标记为待探测，避免探测完成前被判为可播放行并下发。
            // The derivative has not been probed yet: keep it out of the playable set
            // until FFprobe persists a concrete media type.
            derivative.setMediaType(MediaClassifier.PENDING_PROBE);
            derivative.setProbePending(true);
            derivative.setValid(true);
            derivative = files.save(derivative);
            completed = true;
            return new Result(derivative.getId(), output.toString(), Files.size(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        } finally {
            if (!completed) {
                try { Files.deleteIfExists(output); } catch (java.io.IOException ignored) { }
            }
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Process startProcess(List<String> command) throws java.io.IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    public record Result(Long sourceFileId, String outputPath, long outputBytes) {}
}
