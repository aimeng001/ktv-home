package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.LibraryModePolicy;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.SongFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 媒体流（P1.17，详设§11.1）。
 * 支持 HTTP Range，使 MKV/MP4 等大文件秒开、可 seek；TV 端 ExoPlayer 流式拉取。
 * file_id = song_files.id。
 *
 * 用 StreamingResponseBody + RandomAccessFile 手动流字节，完全掌控
 * Content-Type / Content-Range / Content-Length，不受 ResourceRegion 转换器
 * 的 Content-Type 白名单限制（其默认仅接受 octet-stream，video/mp4 会被拒）。
 *
 * Media streaming controller (P1.17, detailed design §11.1).
 * Supports HTTP Range for instant playback and seeking of large files (MKV, MP4, etc.);
 * TV-side ExoPlayer pulls streams progressively.
 * file_id = song_files.id.

/**
 * 媒体流（P1.17，详设§11.1）。
 * 支持 HTTP Range，使 MKV/MP4 等大文件秒开、可 seek；TV 端 ExoPlayer 流式拉取。
 * file_id = song_files.id。
 *
 * 用 StreamingResponseBody + RandomAccessFile 手动流字节，完全掌控
 * Content-Type / Content-Range / Content-Length，不受 ResourceRegion 转换器
 * 的 Content-Type 白名单限制（其默认仅接受 octet-stream，video/mp4 会被拒）。
 *
 * Media streaming controller (P1.17, detailed design §11.1).
 * Supports HTTP Range for instant playback and seeking of large files (MKV, MP4, etc.);
 * TV-side ExoPlayer pulls streams progressively.
 * file_id = song_files.id.
 *
 * Uses StreamingResponseBody + RandomAccessFile to manually stream bytes, giving full
 * control over Content-Type / Content-Range / Content-Length headers, bypassing the
 * ResourceRegion converter's Content-Type whitelist limitation (which defaults to
 * octet-stream only and rejects video/mp4).
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private static final int BUF = 64 * 1024;
    private static final Set<String> TRANSCODE_FORMATS = Set.of("rm", "rmvb", "realmedia");
    private final Semaphore transcodeSemaphore = new Semaphore(4);

    private final SongFileRepository fileRepo;
    private final AppProperties props;
    private final SongAvailabilityPolicy availabilityPolicy;

    @FunctionalInterface
    public interface ProcessLauncher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    private final String ffmpegPath;
    private final ProcessLauncher processLauncher;

    public StreamController(SongFileRepository fileRepo) {
        this(fileRepo, new AppProperties(), new SongAvailabilityPolicy(fileRepo), "ffmpeg", ProcessBuilder::start);
    }

    public StreamController(SongFileRepository fileRepo, AppProperties props) {
        this(fileRepo, props, new SongAvailabilityPolicy(fileRepo), "ffmpeg", ProcessBuilder::start);
    }

    public StreamController(SongFileRepository fileRepo, AppProperties props,
                            SongAvailabilityPolicy availabilityPolicy) {
        this(fileRepo, props, availabilityPolicy, "ffmpeg", ProcessBuilder::start);
    }

    public StreamController(SongFileRepository fileRepo, AppProperties props,
                            SongAvailabilityPolicy availabilityPolicy,
                            ProcessLauncher processLauncher) {
        this(fileRepo, props, availabilityPolicy, "ffmpeg", processLauncher);
    }

    @Autowired
    public StreamController(SongFileRepository fileRepo, AppProperties props,
                            SongAvailabilityPolicy availabilityPolicy,
                            @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath,
                            @Autowired(required = false) ProcessLauncher processLauncher) {
        this.fileRepo = fileRepo;
        this.props = props;
        this.availabilityPolicy = availabilityPolicy;
        this.ffmpegPath = (ffmpegPath != null && !ffmpegPath.isBlank()) ? ffmpegPath : "ffmpeg";
        this.processLauncher = processLauncher != null ? processLauncher : ProcessBuilder::start;
    }

    public ResponseEntity<StreamingResponseBody> stream(Long fileId, String rangeHeader) {
        return stream(fileId, rangeHeader, null);
    }

    /**
     * 流式输出媒体文件，支持 HTTP Range 请求以实现断点续传和 seek。
     *
     * Streams the media file identified by the given file ID, with HTTP Range support
     * for resumable downloads and seeking.
     *
     * @param fileId       歌曲文件 ID（song_files.id）
     *                     Song file ID (song_files.id)
     * @param rangeHeader  HTTP Range 请求头，可空；空时返回完整文件
     *                     HTTP Range request header; when empty, the full file is returned
     * @param startSeconds 可选的起始时间戳（秒），用于老格式管道流的快速定位 seek
     * @return ResponseEntity wrapped over a StreamingResponseBody
     */
    @GetMapping("/stream/{fileId}")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @org.springframework.web.bind.annotation.RequestParam(value = "start", required = false) Double startSeconds) {

        SongFile sf = fileRepo.findById(fileId).orElse(null);
        if (sf == null) {
            return ResponseEntity.notFound().build();
        }
        if (!availabilityPolicy.isReadyFile(sf)) {
            return ResponseEntity.notFound().build();
        }

        if (sf.getFilePath() == null || sf.getFilePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path safePath;
        try {
            safePath = LibraryModePolicy.requireReadablePathInsideActiveLibrary(
                    props, Path.of(sf.getFilePath()));
        } catch (ApiException e) {
            return ResponseEntity.notFound().build();
        }
        File file = safePath.toFile();
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        // 1. 判定是否需要管道流实时转码
        if (isTranscodeRequired(sf)) {
            return serveOnTheFlyTranscodeStream(file, sf, startSeconds);
        }

        // 2. 99% 普通格式快路径：继续走 RandomAccessFile HTTP Range 流
        return serveStaticRangeStream(file, sf, rangeHeader);
    }

    private boolean isTranscodeRequired(SongFile sf) {
        String format = sf.getFormat() == null ? "" : sf.getFormat().trim().toLowerCase();
        String path = sf.getFilePath() == null ? "" : sf.getFilePath().toLowerCase();
        return TRANSCODE_FORMATS.stream().anyMatch(fmt -> format.contains(fmt) || path.endsWith("." + fmt));
    }

    private ResponseEntity<StreamingResponseBody> serveOnTheFlyTranscodeStream(
            File file, SongFile sf, Double startSeconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add(this.ffmpegPath);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        if (startSeconds != null && startSeconds > 0) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", startSeconds));
        }
        cmd.add("-i");
        cmd.add(file.getAbsolutePath());

        // 完整保留原伴唱所有音轨（DUAL_TRACK）
        cmd.add("-map");
        cmd.add("0:v:0");
        int audioTracks = sf.getAudioTracks();
        if (audioTracks > 1) {
            for (int i = 0; i < audioTracks; i++) {
                cmd.add("-map");
                cmd.add("0:a:" + i);
            }
        } else {
            cmd.add("-map");
            cmd.add("0:a?");
        }

        // 视频编码为 H.264
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-tune");
        cmd.add("zerolatency");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");

        // 音频编码为 AAC 双声道立体声（DUAL_CHANNEL 左右声道独立通道）
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add("-ar");
        cmd.add("48000");
        cmd.add("-ac");
        cmd.add("2");

        // 管道输出分片 MP4 流
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add("-movflags");
        cmd.add("frag_keyframe+empty_moov+default_base_moof");
        cmd.add("pipe:1");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(outputStream -> {
                    if (!transcodeSemaphore.tryAcquire()) {
                        throw new IOException("Transcode concurrency limit reached");
                    }
                    Process process = null;
                    try {
                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        process = processLauncher.start(pb);
                        try (InputStream in = new BufferedInputStream(process.getInputStream(), BUF)) {
                            in.transferTo(outputStream);
                            outputStream.flush();
                        }
                    } catch (Exception ignored) {
                        // 客户端断开连接、切歌或快进定位
                    } finally {
                        if (process != null && process.isAlive()) {
                            process.destroyForcibly();
                        }
                        transcodeSemaphore.release();
                    }
                });
    }

    private ResponseEntity<StreamingResponseBody> serveStaticRangeStream(
            File file, SongFile sf, String rangeHeader) {
        long length = file.length();
        MediaType contentType = mediaTypeFor(sf.getFormat(), file.getName());

        // 无 Range：整段返回，声明 Accept-Ranges 以便客户端后续 seek
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(contentType)
                    .contentLength(length)
                    .body(writeRegion(file, 0, length));
        }

        // 有 Range：解析首个区间，返回 206 Partial Content
        long start;
        long end;
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            HttpRange range = ranges.get(0);
            start = range.getRangeStart(length);
            end = range.getRangeEnd(length);
        } catch (IllegalArgumentException e) {
            return rangeNotSatisfiable(length);
        }
        // 起点越界 → 416（不依赖底层抛异常，避免被框架映射成 400）
        if (start >= length || start < 0 || end < start) {
            return rangeNotSatisfiable(length);
        }

        long count = end - start + 1;

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .contentType(contentType)
                .contentLength(count)
                .body(writeRegion(file, start, count));
    }

    private ResponseEntity<StreamingResponseBody> rangeNotSatisfiable(long length) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                .build();
    }

    /**
     * 从指定偏移量开始流式写入 count 字节到输出流。
     *
     * Streams count bytes starting from the given offset into the output stream.
     *
     * @param file   目标文件
     *               The target file
     * @param offset 起始偏移量（字节）
     *               Starting offset in bytes
     * @param count  要流出的字节数
     *               Number of bytes to stream
     * @return StreamingResponseBody 回调
     *         StreamingResponseBody callback
     */
    private StreamingResponseBody writeRegion(File file, long offset, long count) {
        return out -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] buf = new byte[BUF];
                long remaining = count;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = raf.read(buf, 0, toRead);
                    if (read == -1) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
                out.flush();
            }
        };
    }

    /**
     * 按容器格式或文件扩展名映射对应的 Content-Type，无法识别时回退到 octet-stream。
     *
     * Maps a container format or file extension to the corresponding Content-Type,
     * falling back to octet-stream when unrecognized.
     *
     * @param format 容器格式（如 matroska、mp4）
     *               Container format (e.g. matroska, mp4)
     * @param name   文件名（用于提取扩展名）
     *               File name (used to extract the extension)
     * @return 映射后的 MediaType
     *         The resolved MediaType
     */
    private MediaType mediaTypeFor(String format, String name) {
        String f = format == null ? "" : format.toLowerCase();
        String n = name.toLowerCase();
        if (f.contains("matroska") || n.endsWith(".mkv")) return MediaType.parseMediaType("video/x-matroska");
        if (f.contains("mp4") || n.endsWith(".mp4") || n.endsWith(".m4v")) return MediaType.parseMediaType("video/mp4");
        if (n.endsWith(".webm")) return MediaType.parseMediaType("video/webm");
        if (n.endsWith(".avi")) return MediaType.parseMediaType("video/x-msvideo");
        if (f.contains("mpeg") || n.endsWith(".mpg") || n.endsWith(".mpeg")) return MediaType.parseMediaType("video/mpeg");
        if (n.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (n.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return MediaType.parseMediaType("audio/mp4");
        if (n.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (n.endsWith(".ogg")) return MediaType.parseMediaType("audio/ogg");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
