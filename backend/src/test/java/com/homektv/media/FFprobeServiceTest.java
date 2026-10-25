package com.homektv.media;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * FFprobe JSON 解析单测（不依赖外部进程）。
 */
class FFprobeServiceTest {

    private final FFprobeService service = new FFprobeService(new AppProperties());

    @Test
    void probeDoesNotApplyGlobalProbeLimits() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        FFprobeService service = new FFprobeService(new AppProperties(), args -> {
            command.set(args);
            return new CompletedProcess("{\"streams\":[],\"format\":{}}", "");
        }, Duration.ofSeconds(1));

        service.probe(Path.of("sample.ts"));

        assertThat(command.get()).doesNotContain("-probesize", "-analyzeduration");
    }

    @Test
    void rejectsMissingZeroAndNegativeTimeouts() {
        assertThat(catchThrowable(() -> new FFprobeService(new AppProperties(), args -> new CompletedProcess("", ""), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new FFprobeService(new AppProperties(), args -> new CompletedProcess("", ""), Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new FFprobeService(new AppProperties(), args -> new CompletedProcess("", ""), Duration.ofMillis(-1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapsProcessStartupIoFailureWithTheConfiguredProbePath() {
        FFprobeService service = new FFprobeService(new AppProperties(), args -> {
            throw new IOException("synthetic start failure");
        }, Duration.ofSeconds(1));

        assertThat(catchThrowable(() -> service.probe(Path.of("missing.ts"))))
                .isInstanceOf(MediaProbeException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasMessageContaining("ffprobe");
    }

    @Test
    void rejectsNonzeroProcessExitAndIncludesTrimmedStderr() {
        FFprobeService service = new FFprobeService(new AppProperties(), args -> new CompletedProcess(
                "{\"streams\":[],\"format\":{}}", "  unsupported codec  ", 7), Duration.ofSeconds(1));

        assertThat(catchThrowable(() -> service.probe(Path.of("unsupported.ts"))))
                .isInstanceOf(MediaProbeException.class)
                .hasMessageContaining("exit=7")
                .hasMessageContaining("unsupported codec");
    }

    @Test
    void restoresInterruptStatusAndDestroysTheProbeProcess() {
        InterruptibleProcess process = new InterruptibleProcess();
        FFprobeService service = new FFprobeService(new AppProperties(), args -> process, Duration.ofSeconds(1));

        try {
            assertThat(catchThrowable(() -> service.probe(Path.of("interrupted.ts"))))
                    .isInstanceOf(MediaProbeException.class)
                    .hasMessageContaining("中断");
            assertThat(process.destroyed.get()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ignoresZeroLengthReadsAndContinuesDrainingUtf8Output() {
        String json = "{\"streams\":[],\"format\":{}}";
        FFprobeService service = new FFprobeService(new AppProperties(), args -> new CompletedProcess(
                new ZeroReadThenDataInputStream(json), new ByteArrayInputStream(new byte[0])), Duration.ofSeconds(1));

        MediaProbe result = service.probe(Path.of("zero-read.ts"));

        assertThat(result.audioTracks()).isZero();
        assertThat(result.durationMs()).isZero();
    }

    @Test
    void probeTimeoutDestroysProcessWhenOutputDoesNotClose() {
        AtomicReference<HangingProcess> process = new AtomicReference<>();
        FFprobeService service = new FFprobeService(new AppProperties(), args -> {
            HangingProcess hanging = new HangingProcess();
            process.set(hanging);
            return hanging;
        }, Duration.ofMillis(100));

        Throwable failure = catchThrowable(() -> assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> service.probe(Path.of("hung.ts"))));

        assertThat(failure).isNotNull();
        assertThat(process.get().destroyed.get()).isTrue();
        assertThat(process.get().waitedAfterDestroy.get()).isTrue();
    }

    @Test
    void oversizedStdoutIsRejectedAndProcessIsDestroyed() {
        AtomicReference<OutputProcess> process = new AtomicReference<>();
        FFprobeService service = new FFprobeService(new AppProperties(), args -> {
            OutputProcess outputProcess = new OutputProcess(true);
            process.set(outputProcess);
            return outputProcess;
        }, Duration.ofSeconds(1));

        Throwable failure = catchThrowable(() -> service.probe(Path.of("large-stdout.ts")));

        assertThat(failure)
                .isInstanceOf(MediaProbeException.class)
                .hasMessageContaining("ffprobe 输出超过上限");
        assertThat(process.get().destroyed.get()).isTrue();
    }

    @Test
    void oversizedStderrIsRejectedAndProcessIsDestroyed() {
        AtomicReference<OutputProcess> process = new AtomicReference<>();
        FFprobeService service = new FFprobeService(new AppProperties(), args -> {
            OutputProcess outputProcess = new OutputProcess(false);
            process.set(outputProcess);
            return outputProcess;
        }, Duration.ofSeconds(1));

        Throwable failure = catchThrowable(() -> service.probe(Path.of("large-stderr.ts")));

        assertThat(failure)
                .isInstanceOf(MediaProbeException.class)
                .hasMessageContaining("ffprobe 输出超过上限");
        assertThat(process.get().destroyed.get()).isTrue();
    }

    @Test
    void parseKtvVideoWithDualAudio() {
        // 模拟 A 类 KTV 视频：1 视频流 + 2 音轨（原唱/伴奏）+ 1 字幕流
        String json = """
            {
              "streams": [
                {"codec_type": "video", "width": 1920, "height": 1080, "disposition": {"attached_pic": 0}},
                {"codec_type": "audio"},
                {"codec_type": "audio"},
                {"codec_type": "subtitle"}
              ],
              "format": {"duration": "269.500000"}
            }
            """;
        MediaProbe p = service.parse(json);
        assertThat(p.hasVideo()).isTrue();
        assertThat(p.audioTracks()).isEqualTo(2);
        assertThat(p.subtitleTracks()).isEqualTo(1);
        assertThat(p.resolution()).isEqualTo("1920x1080");
        assertThat(p.durationMs()).isEqualTo(269500L);
    }

    @Test
    void parseAudioOnly() {
        // 纯音频（B 类 AUDIO）：仅 1 音轨，无视频
        String json = """
            {
              "streams": [
                {"codec_type": "audio"}
              ],
              "format": {"duration": "241.000000"}
            }
            """;
        MediaProbe p = service.parse(json);
        assertThat(p.hasVideo()).isFalse();
        assertThat(p.audioTracks()).isEqualTo(1);
        assertThat(p.resolution()).isNull();
        assertThat(p.durationMs()).isEqualTo(241000L);
    }

    @Test
    void parseAudioStreamMetadata() {
        // 双音轨：track0 原唱、track1 伴奏（karaoke 标志 + 标题），验证元数据采集
        String json = """
            {
              "streams": [
                {"codec_type": "video", "width": 1920, "height": 1080, "disposition": {"attached_pic": 0}},
                {"codec_type": "audio", "channels": 2,
                 "disposition": {"default": 1, "karaoke": 0}, "tags": {"title": "原唱", "language": "zho"}},
                {"codec_type": "audio", "channels": 2,
                 "disposition": {"default": 0, "karaoke": 1}, "tags": {"title": "伴奏"}}
              ],
              "format": {"duration": "269.5"}
            }
            """;
        MediaProbe p = service.parse(json);
        assertThat(p.audioStreams()).hasSize(2);
        AudioStreamInfo a0 = p.audioStreams().get(0);
        assertThat(a0.index()).isEqualTo(0);
        assertThat(a0.title()).isEqualTo("原唱");
        assertThat(a0.language()).isEqualTo("zho");
        assertThat(a0.channels()).isEqualTo(2);
        assertThat(a0.isDefault()).isTrue();
        assertThat(a0.karaoke()).isFalse();
        AudioStreamInfo a1 = p.audioStreams().get(1);
        assertThat(a1.index()).isEqualTo(1);
        assertThat(a1.title()).isEqualTo("伴奏");
        assertThat(a1.karaoke()).isTrue();
    }

    @Test
    void attachedCoverImageNotCountedAsVideo() {
        // MP3 内嵌封面图会有一个 attached_pic 视频流，不应判为有视频
        String json = """
            {
              "streams": [
                {"codec_type": "audio"},
                {"codec_type": "video", "width": 500, "height": 500, "disposition": {"attached_pic": 1}}
              ],
              "format": {"duration": "180.0"}
            }
            """;
        MediaProbe p = service.parse(json);
        assertThat(p.hasVideo()).isFalse();
        assertThat(p.audioTracks()).isEqualTo(1);
        assertThat(p.resolution()).isNull();
    }

    @Test
    void parseHandlesFallbackTagsAlternateStreamTitleAndUnusableVideoDimensions() {
        String json = """
            {
              "format": {"duration": "180.5", "tags": {"title": "  ", "album_artist": " Artist ", "language": "zh"}},
              "streams": [
                {"codec_type": "video", "width": 0, "height": 1080, "disposition": {"attached_pic": 0}},
                {"codec_type": "data"},
                {"codec_type": "audio", "codec_name": "aac", "channels": 2, "tags": {"title": " ", "name": " Lead "}},
                {"codec_type": "subtitle"}
              ]
            }
            """;

        MediaProbe probe = service.parse(json);

        assertThat(probe.hasVideo()).isTrue();
        assertThat(probe.resolution()).isNull();
        assertThat(probe.subtitleTracks()).isEqualTo(1);
        assertThat(probe.audioTracks()).isEqualTo(1);
        assertThat(probe.audioCodec()).isEqualTo("aac");
        assertThat(probe.audioStreams().getFirst().title()).isEqualTo("Lead");
        assertThat(probe.artist()).isEqualTo("Artist");
        assertThat(probe.language()).isEqualTo("zh");
        assertThat(probe.title()).isNull();
    }

    @Test
    void rejectsMalformedJsonInsteadOfReturningAnEmptyProbe() {
        assertThat(catchThrowable(() -> service.parse("{ invalid json")))
                .isInstanceOf(MediaProbeException.class)
                .hasMessageContaining("解析 ffprobe JSON 失败");
    }

    private static final class CompletedProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;

        private CompletedProcess(String stdout, String stderr) {
            this(new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0);
        }

        private CompletedProcess(String stdout, String stderr, int exitCode) {
            this(new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8)), exitCode);
        }

        private CompletedProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, 0);
        }

        private CompletedProcess(InputStream stdout, InputStream stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class ZeroReadThenDataInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean returnZero = true;

        private ZeroReadThenDataInputStream(String value) {
            delegate = new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public int read() { return delegate.read(); }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (returnZero) {
                returnZero = false;
                return 0;
            }
            return delegate.read(buffer, offset, length);
        }
    }

    private static final class InterruptibleProcess extends Process {
        private final AtomicBoolean destroyed = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { throw new InterruptedException("synthetic interrupt"); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("synthetic timed wait interrupt");
        }
        @Override public int exitValue() { return destroyed.get() ? 143 : 0; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { destroyed.set(true); return this; }
        @Override public boolean isAlive() { return !destroyed.get(); }
    }

    private static final class HangingProcess extends Process {
        private final BlockingInputStream stdout = new BlockingInputStream();
        private final BlockingInputStream stderr = new BlockingInputStream();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean waitedAfterDestroy = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException {
            while (!destroyed.get()) Thread.sleep(10);
            return 1;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (destroyed.get()) {
                waitedAfterDestroy.set(true);
                return true;
            }
            unit.sleep(timeout);
            return destroyed.get();
        }
        @Override public int exitValue() {
            if (!destroyed.get()) throw new IllegalThreadStateException("process is still running");
            return 1;
        }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            destroyed.set(true);
            stdout.closeQuietly();
            stderr.closeQuietly();
            return this;
        }
        @Override public boolean isAlive() { return !destroyed.get(); }
    }

    private static final class BlockingInputStream extends InputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            while (!closed.get()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("reader interrupted");
                }
                Thread.onSpinWait();
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private void closeQuietly() {
            close();
        }
    }

    private static final class OutputProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private OutputProcess(boolean largeStdout) {
            InputStream large = largeStdout ? largeJson() : new RepeatingInputStream(FFprobeService.MAX_OUTPUT_BYTES + 1L);
            InputStream small = new ByteArrayInputStream("{\"streams\":[],\"format\":{}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdout = largeStdout ? large : small;
            stderr = largeStdout ? small : large;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            destroyed.set(true);
            try { stdout.close(); } catch (IOException ignored) { }
            try { stderr.close(); } catch (IOException ignored) { }
            return this;
        }
        @Override public boolean isAlive() { return !destroyed.get(); }

        private static InputStream largeJson() {
            byte[] prefix = "{\"streams\":[],\"format\":{\"padding\":\""
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] suffix = "\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new SequenceInputStream(
                    new ByteArrayInputStream(prefix),
                    new SequenceInputStream(
                            new RepeatingInputStream(FFprobeService.MAX_OUTPUT_BYTES + 1L),
                            new ByteArrayInputStream(suffix)));
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 'x';
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
        }
    }
}
