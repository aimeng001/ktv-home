package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaTranscoder {

    static final int MAX_PROCESS_LOG_BYTES = 64 * 1024;

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private final TranscodeHardwareService hardwareService;
    private final String ffmpegPath;
    private final AppProperties props;
    private final ProcessLauncher processLauncher;

    public MediaTranscoder(TranscodeHardwareService hardwareService,
                           @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this(hardwareService, ffmpegPath, new AppProperties(), MediaTranscoder::startProcess,
                MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    @Autowired
    public MediaTranscoder(TranscodeHardwareService hardwareService,
                           @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath,
                           AppProperties props,
                           @Value("${app.transcode.timeout-seconds:1800}") long timeoutSeconds) {
        this(hardwareService, ffmpegPath, props, MediaTranscoder::startProcess,
                MediaProcessRunner.fromSeconds(timeoutSeconds));
    }

    /** Compatibility constructor for direct callers that use the managed-mode service. */
    public MediaTranscoder(TranscodeHardwareService hardwareService, String ffmpegPath, AppProperties props) {
        this(hardwareService, ffmpegPath, props, MediaTranscoder::startProcess,
                MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    MediaTranscoder(TranscodeHardwareService hardwareService, String ffmpegPath,
                    AppProperties props, ProcessLauncher processLauncher) {
        this(hardwareService, ffmpegPath, props, processLauncher, MediaProcessRunner.DEFAULT_TIMEOUT);
    }

    MediaTranscoder(TranscodeHardwareService hardwareService, String ffmpegPath,
                    AppProperties props, ProcessLauncher processLauncher, Duration timeout) {
        this.hardwareService = hardwareService;
        this.ffmpegPath = ffmpegPath;
        this.props = props;
        this.processLauncher = processLauncher;
        this.timeout = timeout;
    }

    private final Duration timeout;

    public Path transcode(Path source, Path output, SettingService.TranscodePolicy policy, boolean hasVideo) {
        LibraryModePolicy.requireManaged(props, "转码源文件");
        List<String> command = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y"));
        boolean hardware = policy.hardwareAcceleration() && hasVideo;
        TranscodeHardwareService.HardwareStatus hardwareStatus = null;
        if (hardware) {
            hardwareStatus = hardwareService.requireAvailable(policy.videoCodec());
            if ("vaapi".equals(hardwareStatus.acceleration())) {
                command.addAll(List.of("-vaapi_device", hardwareStatus.device()));
            }
        }
        command.addAll(List.of("-i", source.toString(), "-map", "0:v:0?", "-map", "0:a?"));
        if (hasVideo) {
            if (hardware) {
                if ("rkmpp".equals(hardwareStatus.acceleration())) {
                    command.addAll(List.of("-pix_fmt", "nv12", "-c:v", policy.videoCodec() + "_rkmpp"));
                } else {
                    command.addAll(List.of("-vf", "format=nv12,hwupload", "-c:v", policy.videoCodec() + "_vaapi"));
                }
            } else {
                command.addAll(List.of("-c:v", "hevc".equals(policy.videoCodec()) ? "libx265" : "libx264",
                        "-pix_fmt", "yuv420p"));
            }
        }
        Path reservedOutput;
        try {
            reservedOutput = OutputPathReservation.reserve(output);
        } catch (IOException e) {
            throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED",
                    e.getMessage());
        }
        command.addAll(List.of("-c:a", audioEncoder(policy.audioCodec()), "-b:a", "192k", "-ar", "48000",
                "-c:s", "copy", reservedOutput.toString()));

        boolean completed = false;
        try {
            Process process = processLauncher.start(command);
            MediaProcessRunner.Result result = MediaProcessRunner.run(process, timeout);
            if (result.timedOut()) {
                throw new ApiException("TRANSCODE_TIMEOUT", "ffmpeg 转码超时");
            }
            String log = result.output();
            int code = result.exitCode();
            if (code != 0 || !Files.isReadable(reservedOutput) || Files.size(reservedOutput) == 0) {
                throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED",
                        log.isBlank() ? "ffmpeg 转码失败" : log);
            }
            completed = true;
            return reservedOutput;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (IOException e) {
            throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED", e.getMessage());
        } finally {
            if (!completed) {
                try { Files.deleteIfExists(reservedOutput); } catch (IOException ignored) { }
            }
        }
    }

    private static Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static String audioEncoder(String codec) {
        return switch (codec) {
            case "mp3" -> "libmp3lame";
            case "opus" -> "libopus";
            default -> "aac";
        };
    }
}
