package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates a TV-compatible H.264/AAC derivative without touching the source file. */
@Service
public class TranscodeService {
    private final SongFileRepository files;
    private final AppProperties props;

    /** Compatibility constructor for callers that use the original managed-mode service directly. */
    public TranscodeService(SongFileRepository files) {
        this(files, new AppProperties());
    }

    @Autowired
    public TranscodeService(SongFileRepository files, AppProperties props) {
        this.files = files;
        this.props = props;
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
        Path input = Path.of(source.getFilePath());
        if (!Files.isReadable(input)) throw new ApiException("FILE_NOT_FOUND", "源文件不可读：" + input);
        Path output = uniqueOutput(input, "mkv");
        SongFile existing = files.findByFilePath(output.toString()).orElse(null);
        try {
            if (existing != null && Files.isReadable(output) && Files.size(output) > 0) {
                return new Result(existing.getId(), output.toString(), Files.size(output));
            }
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
        try {
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-map", "0:v:0?", "-map", "0:a?",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "high",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-c:s", "copy", output.toString())
                    .redirectErrorStream(true).start();
            String log = new String(process.getInputStream().readAllBytes());
            int code = process.waitFor();
            if (code != 0 || !Files.isReadable(output)) throw new ApiException("TRANSCODE_FAILED", log);
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
            derivative.setValid(true);
            derivative = files.save(derivative);
            return new Result(derivative.getId(), output.toString(), Files.size(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path uniqueOutput(Path input, String extension) {
        Path desired = input.resolveSibling(stripExtension(input.getFileName().toString()) + "." + extension);
        if (!desired.equals(input) && !Files.exists(desired)) return desired;
        String baseName = stripExtension(input.getFileName().toString());
        for (int index = 2; index < 10000; index++) {
            Path candidate = input.resolveSibling(baseName + "-" + index + "." + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new ApiException("TARGET_NAME_EXHAUSTED", "无法为输出文件分配唯一文件名：" + desired);
    }

    public record Result(Long sourceFileId, String outputPath, long outputBytes) {}
}
