package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaTranscoder {

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
        this(hardwareService, ffmpegPath, new AppProperties(), MediaTranscoder::startProcess);
    }

    @Autowired
    public MediaTranscoder(TranscodeHardwareService hardwareService,
                           @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath,
                           AppProperties props) {
        this(hardwareService, ffmpegPath, props, MediaTranscoder::startProcess);
    }

    MediaTranscoder(TranscodeHardwareService hardwareService, String ffmpegPath,
                    AppProperties props, ProcessLauncher processLauncher) {
        this.hardwareService = hardwareService;
        this.ffmpegPath = ffmpegPath;
        this.props = props;
        this.processLauncher = processLauncher;
    }

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
        command.addAll(List.of("-c:a", audioEncoder(policy.audioCodec()), "-b:a", "192k", "-ar", "48000",
                "-c:s", "copy", output.toString()));

        boolean completed = false;
        try {
            Process process = processLauncher.start(command);
            String log = new String(process.getInputStream().readAllBytes());
            int code = process.waitFor();
            if (code != 0 || !Files.isReadable(output) || Files.size(output) == 0) {
                throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED",
                        log.isBlank() ? "ffmpeg 转码失败" : log);
            }
            completed = true;
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (IOException e) {
            throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED", e.getMessage());
        } finally {
            if (!completed) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) { }
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
