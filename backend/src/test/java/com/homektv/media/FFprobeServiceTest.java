package com.homektv.media;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final class CompletedProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;

        private CompletedProcess(String stdout, String stderr) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class HangingProcess extends Process {
        private final BlockingInputStream stdout = new BlockingInputStream();
        private final BlockingInputStream stderr = new BlockingInputStream();
        private final AtomicBoolean destroyed = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException {
            while (!destroyed.get()) Thread.sleep(10);
            return 1;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
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
}
